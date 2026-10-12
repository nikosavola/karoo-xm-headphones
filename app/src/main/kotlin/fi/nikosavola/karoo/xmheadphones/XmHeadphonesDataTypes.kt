package fi.nikosavola.karoo.xmheadphones

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.layout.Alignment as GlanceAlignment
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.color.ColorProvider
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/** The type ids the extension manifest declares. A mismatch registers a field with nothing behind it. */
internal object FieldIds {
    const val BATTERY = "battery"
    const val AMBIENT_LEVEL = "ambient-level"
    const val NOISE_CANCELLING = "noise-cancelling"
}

/**
 * A field that Karoo draws itself: the title from the extension manifest and one number. That is
 * what makes a battery or an ambient level look like a built-in field rather than an imitation.
 */
internal abstract class NumericField(
    private val client: XmHeadphonesClient,
    extension: String,
    typeId: String,
) : DataTypeImpl(extension, typeId) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Null while the headset has not answered that part of its state yet. */
    protected abstract fun value(state: XmHeadphonesState): Double?

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

    private fun XmHeadphonesState.toStreamState(): StreamState {
        val current = value(this)
        return when {
            link == HeadphonesLink.Connected && current != null ->
                StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to current)))
            // Connected but still waiting for this particular value, or on the way there: Karoo shows
            // its own "Searching..." text, which is the right thing for a rider to see.
            link == HeadphonesLink.Connected ||
                link == HeadphonesLink.Searching ||
                link == HeadphonesLink.Connecting -> StreamState.Searching
            else -> StreamState.NotAvailable
        }
    }

}

internal class BatteryDataType(client: XmHeadphonesClient, extension: String) :
    NumericField(client, extension, FieldIds.BATTERY) {
    override fun value(state: XmHeadphonesState): Double? = state.batteryPercent?.toDouble()
}

internal class AmbientLevelDataType(client: XmHeadphonesClient, extension: String) :
    NumericField(client, extension, FieldIds.AMBIENT_LEVEL) {
    override fun value(state: XmHeadphonesState): Double? =
        (state.noiseCancelling as? NoiseCancelling.Ambient)?.level?.steps?.toDouble()
}

/**
 * The noise control mode, which has no number to show: it is text, so the field is drawn by the
 * extension instead of by Karoo.
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
                    .map { it.noiseCancelling to it.link }
                    // Only on a real change: composing RemoteViews is far too heavy for a ride
                    // screen's refresh rate and this value changes when the rider presses a button.
                    .distinctUntilChanged()
                    .collect { (state, link) ->
                        emitter.updateView(compose(context, config, state, link).remoteViews)
                    }
            }
        emitter.setCancellable {
            Timber.d("Stop the noise cancelling view")
            job.cancel()
        }
    }

    private suspend fun compose(
        context: Context,
        config: ViewConfig,
        state: NoiseCancelling?,
        link: HeadphonesLink,
    ) = glance.compose(context, DpSize.Unspecified) {
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(4.dp),
            contentAlignment = GlanceAlignment.Center,
        ) {
            Text(
                text = label(context, state, link),
                style =
                    TextStyle(
                        fontSize = TextUnit(config.textSize.toFloat(), TextUnitType.Sp),
                        textAlign = TextAlign.Center,
                        color = ColorProvider(Color.Black, Color.White),
                    ),
            )
        }
    }

    private fun label(
        context: Context,
        state: NoiseCancelling?,
        link: HeadphonesLink,
    ): String =
        when {
            link != HeadphonesLink.Connected -> context.getString(R.string.state_searching)
            state is NoiseCancelling.Cancelling -> context.getString(R.string.nc_noise_cancelling)
            state is NoiseCancelling.Ambient ->
                if (state.focusOnVoice) {
                    context.getString(R.string.nc_ambient_voice)
                } else {
                    context.getString(R.string.nc_ambient)
                }
            state is NoiseCancelling.Off -> context.getString(R.string.nc_off)
            // Connected, but the headset does not offer noise control at all.
            else -> context.getString(R.string.state_unsupported)
        }
}

/** The wire ids of every field this extension declares, for matching against a ride profile. */
internal fun dataTypeIdsOf(extension: String): List<String> =
    listOf(FieldIds.BATTERY, FieldIds.NOISE_CANCELLING, FieldIds.AMBIENT_LEVEL).map {
        DataType.dataTypeId(extension, it)
    }
