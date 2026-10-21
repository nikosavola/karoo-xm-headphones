package fi.nikosavola.karoo.xmheadphones

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment as GlanceAlignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The type ids the extension manifest declares.
 *
 * A mismatch registers a field that never leaves Searching. Keep the two in step.
 */
internal object FieldIds {
    const val BATTERY = "battery"
    const val AMBIENT_LEVEL = "ambient-level"
    const val NOISE_CANCELLING = "noise-cancelling"

    /** Every declared field, for matching a ride profile against them. */
    val ALL = listOf(BATTERY, NOISE_CANCELLING, AMBIENT_LEVEL)
}

/**
 * A field Karoo draws itself, manifest title plus one number. That is why a battery or ambient
 * level looks built in.
 */
internal abstract class NumericField(
    private val client: XmHeadphonesClient,
    extension: String,
    typeId: String,
) : DataTypeImpl(extension, typeId) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * What the field has to show.
     *
     * Karoo shows "Searching" for [Pending] and "unavailable" for [Unavailable]. A nullable
     * number could not tell them apart and would search for the whole ride.
     */
    protected sealed interface Reading {
        @JvmInline value class Value(val value: Double) : Reading

        /** The headset has not answered this yet, but it may still. */
        data object Pending : Reading

        /** The headset is not going to answer this. */
        data object Unavailable : Reading
    }

    protected abstract fun reading(state: XmHeadphonesState): Reading

    override fun startStream(emitter: Emitter<StreamState>) {
        Timber.d("Start $typeId stream")
        val job =
            scope.launch {
                client.state
                    .map { it.toStreamState() }
                    .distinctUntilChanged()
                    .collect { emitter.onNext(it) }
            }
        emitter.setCancellable {
            Timber.d("Stop $typeId stream")
            job.cancel()
        }
    }

    private fun XmHeadphonesState.toStreamState(): StreamState =
        when {
            link == HeadphonesLink.Searching || link == HeadphonesLink.Connecting ->
                StreamState.Searching
            // Nothing paired, no permission and a radio that is off all read as nothing to show. Only
            // a link that could still come up is worth a spinner on a ride page, and a rider who has
            // yet to pair a headset would watch one turn for the whole ride.
            link != HeadphonesLink.Connected -> StreamState.NotAvailable
            else ->
                when (val reading = reading(this)) {
                    is Reading.Value ->
                        StreamState.Streaming(
                            DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to reading.value)),
                        )
                    Reading.Pending -> StreamState.Searching
                    Reading.Unavailable -> StreamState.NotAvailable
                }
        }
}

internal class BatteryDataType(client: XmHeadphonesClient, extension: String) :
    NumericField(client, extension, FieldIds.BATTERY) {
    override fun reading(state: XmHeadphonesState): Reading {
        // A computed property cannot be smart cast.
        val percent = state.batteryPercent
        return when {
            percent != null -> Reading.Value(percent.toDouble())
            // The handshake is done, so a headset without a battery function never reports one.
            state.capabilities?.batteryShape == null -> Reading.Unavailable
            else -> Reading.Pending
        }
    }
}

internal class AmbientLevelDataType(client: XmHeadphonesClient, extension: String) :
    NumericField(client, extension, FieldIds.AMBIENT_LEVEL) {
    override fun reading(state: XmHeadphonesState): Reading {
        // Only ambient mode carries a level, so the rider's last one stands in. The slider
        // returns to it.
        val level =
            (state.noiseCancelling as? NoiseCancelling.Ambient)?.level ?: state.lastAmbientLevel
        return when {
            level != null -> Reading.Value(level.steps.toDouble())
            !state.supportsNoiseCancelling -> Reading.Unavailable
            // The report arrived without a level, and levels only arrive in ambient mode. Waiting
            // longer searches the whole ride.
            state.noiseCancelling != null -> Reading.Unavailable
            else -> Reading.Pending
        }
    }
}

/**
 * The mode has no number to show, so the extension draws the text itself.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
internal class NoiseCancellingDataType(
    private val client: XmHeadphonesClient,
    extension: String,
) : DataTypeImpl(extension, FieldIds.NOISE_CANCELLING) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Timber.d("Start the noise cancelling view")
        val job =
            scope.launch {
                client.state
                    .map { noiseCancellingLabel(context, it) }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { label ->
                        emitter.updateView(compose(context, config, label).remoteViews)
                        // The emitter drops an update within 900 ms of the last and never re-sends
                        // it, so renders are spaced past that. Conflation turns a mid-gap change
                        // into the next render, so nothing is lost.
                        delay(MIN_RENDER_INTERVAL_MS)
                    }
            }
        emitter.setCancellable {
            Timber.d("Stop the noise cancelling view")
            job.cancel()
        }
    }

    private suspend fun compose(context: Context, config: ViewConfig, label: String) =
        glance.compose(context, DpSize.Unspecified) {
            Box(
                modifier = GlanceModifier.fillMaxSize().padding(4.dp),
                contentAlignment = GlanceAlignment.Center,
            ) {
                Text(
                    text = label,
                    style =
                        TextStyle(
                            fontSize = TextUnit(config.textSize.toFloat(), TextUnitType.Sp),
                            textAlign = TextAlign.Center,
                            color = ColorProvider(Color.Black, Color.White),
                        ),
                )
            }
        }

    private companion object {
        const val MIN_RENDER_INTERVAL_MS = 1_000L
    }
}

/**
 * What a ride screen writes in the noise cancelling field.
 *
 * A headset that offers noise control but has not reported a mode yet is not the same as one that
 * never offers it, and neither is one whose link is still coming up.
 */
internal fun noiseCancellingLabel(context: Context, state: XmHeadphonesState): String {
    // A property from another module cannot be smart cast.
    val noiseCancelling = state.noiseCancelling
    return when {
        state.link == HeadphonesLink.NoPermission -> context.getString(R.string.state_no_permission)
        state.link == HeadphonesLink.BluetoothOff -> context.getString(R.string.state_bluetooth_off)
        state.link == HeadphonesLink.NoPairedHeadset -> context.getString(R.string.state_no_headset)
        state.link != HeadphonesLink.Connected -> context.getString(R.string.state_searching)
        noiseCancelling is NoiseCancelling.Cancelling ->
            context.getString(R.string.nc_noise_cancelling)
        noiseCancelling is NoiseCancelling.Ambient ->
            if (noiseCancelling.focusOnVoice) {
                context.getString(R.string.nc_ambient_voice)
            } else {
                context.getString(R.string.nc_ambient)
            }
        noiseCancelling is NoiseCancelling.Off -> context.getString(R.string.nc_off)
        state.supportsNoiseCancelling -> context.getString(R.string.state_unknown)
        else -> context.getString(R.string.state_unsupported)
    }
}

/** Wire ids of every declared field, for ride-profile matching. */
internal fun dataTypeIdsOf(extension: String): List<String> =
    FieldIds.ALL.map { DataType.dataTypeId(extension, it) }
