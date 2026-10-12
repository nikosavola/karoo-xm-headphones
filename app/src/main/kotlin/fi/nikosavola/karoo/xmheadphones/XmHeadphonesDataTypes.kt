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
 * A mismatch registers a field Karoo draws with nothing behind it, which shows up as a field that
 * never leaves Searching, so the manifest and these constants have to agree.
 */
internal object FieldIds {
    const val BATTERY = "battery"
    const val AMBIENT_LEVEL = "ambient-level"
    const val NOISE_CANCELLING = "noise-cancelling"
}

/**
 * A field Karoo draws itself: the title from the extension manifest and one number. That is what
 * makes a battery or an ambient level look like a built-in field rather than an imitation.
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
     * The difference between [Pending] and [Unavailable] is the whole reason this is not a nullable
     * number: a headset that has not answered yet is worth waiting for, and one that will not answer
     * is not, and Karoo shows "Searching" for the first and "unavailable" for the second. A field that
     * cannot tell them apart searches for the whole ride.
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
        // Bound locally: the property is computed, so it cannot be smart cast.
        val percent = state.batteryPercent
        return when {
            percent != null -> Reading.Value(percent.toDouble())
            // Connected, so the handshake is done: a headset with no battery function is not going to
            // report one however long this waits.
            state.capabilities?.batteryShape == null -> Reading.Unavailable
            else -> Reading.Pending
        }
    }
}

internal class AmbientLevelDataType(client: XmHeadphonesClient, extension: String) :
    NumericField(client, extension, FieldIds.AMBIENT_LEVEL) {
    override fun reading(state: XmHeadphonesState): Reading {
        // Only ambient mode carries a level, so the level the rider last had stands in while noise
        // cancelling or off is on, which is the level the slider will come back to.
        val level =
            (state.noiseCancelling as? NoiseCancelling.Ambient)?.level ?: state.lastAmbientLevel
        return when {
            level != null -> Reading.Value(level.steps.toDouble())
            !state.supportsNoiseCancelling -> Reading.Unavailable
            // Its noise control report arrived and carried no level, and it reports the level only
            // while it is in ambient mode. Waiting for a later one would search for the whole ride.
            state.noiseCancelling != null -> Reading.Unavailable
            else -> Reading.Pending
        }
    }
}

/**
 * The noise control mode, which has no number to show: it is text, so the extension draws the field
 * instead of Karoo.
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
                    .map { labelOf(context, it) }
                    .distinctUntilChanged()
                    .conflate()
                    .collect { label ->
                        emitter.updateView(compose(context, config, label).remoteViews)
                        // The view emitter drops an update that lands within 900 ms of the last one
                        // and never re-sends it, so renders are spaced past that. Conflation means a
                        // change arriving in the meantime becomes the next render rather than being
                        // lost, so nothing the rider changed goes unshown.
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

    private fun labelOf(context: Context, state: XmHeadphonesState): String {
        // Bound to a local: the property comes from another module, so it cannot be smart cast.
        val noiseCancelling = state.noiseCancelling
        return when {
            state.link == HeadphonesLink.NoPermission ->
                context.getString(R.string.state_no_permission)
            state.link == HeadphonesLink.BluetoothOff ->
                context.getString(R.string.state_bluetooth_off)
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
            // Connected, but the headset does not offer noise control at all.
            else -> context.getString(R.string.state_unsupported)
        }
    }

    private companion object {
        const val MIN_RENDER_INTERVAL_MS = 1_000L
    }
}

/** The wire ids of every field this extension declares, for matching against a ride profile. */
internal fun dataTypeIdsOf(extension: String): List<String> =
    listOf(FieldIds.BATTERY, FieldIds.NOISE_CANCELLING, FieldIds.AMBIENT_LEVEL).map {
        DataType.dataTypeId(extension, it)
    }
