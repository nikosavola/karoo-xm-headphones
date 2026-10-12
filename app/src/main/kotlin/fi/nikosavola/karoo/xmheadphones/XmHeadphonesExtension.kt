package fi.nikosavola.karoo.xmheadphones

import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The extension Karoo binds to. Bound by the system for as long as the extension is installed.
 *
 * The headset connection is not tied to this service being alive: it is held while the active ride
 * profile shows one of the fields, so a rider on a bike whose profile has no headphone field is not
 * carrying a scan around. A controller button assigned to the toggle action borrows the connection
 * for as long as it needs it.
 */
class XmHeadphonesExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var client: XmHeadphonesClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guarded by [setHoldingClient] and [releaseClient]: a profile event and teardown can race. */
    private var holdsClient = false

    override val types by lazy {
        listOf(
            BatteryDataType(client, extension),
            AmbientLevelDataType(client, extension),
            NoiseCancellingDataType(client, extension),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("Extension service created")
        client = XmHeadphonesClient.get(this)
        karooSystem = KarooSystemService(this)
        karooSystem.connect { connected ->
            Timber.i("Karoo system connected=$connected")
            // Ask Karoo to leave the Bluetooth radio on for us.
            if (connected) karooSystem.dispatch(RequestBluetooth(extension))
        }
        karooSystem.addConsumer<ActiveRideProfile> { event -> onActiveProfile(event.profile) }
        scope.launch { alertOnDisconnect() }
    }

    /**
     * A headset that drops mid-ride is worth saying out loud: the rider notices the silence, and
     * otherwise cannot tell whether it is the headset or the music that stopped.
     */
    private suspend fun alertOnDisconnect() {
        var wasConnected = false
        client.state
            .map { it.link }
            .distinctUntilChanged()
            .collect { link ->
                val dropped =
                    wasConnected && link != HeadphonesLink.Connected && link != HeadphonesLink.Idle
                if (dropped) {
                    karooSystem.dispatch(
                        InRideAlert(
                            id = "sony-headphones-disconnected",
                            icon = R.drawable.ic_headphones,
                            title = getString(R.string.alert_disconnected_title),
                            detail = getString(R.string.alert_disconnected_detail),
                            autoDismissMs = 10_000,
                            backgroundColor = R.color.alert_background,
                            textColor = R.color.alert_text,
                        ),
                    )
                }
                wasConnected = link == HeadphonesLink.Connected
            }
    }

    private fun onActiveProfile(profile: RideProfile) {
        val wanted =
            profile.pages.any { page ->
                page.elements.any { element ->
                    dataTypeIdsOf(extension).any { element.dataTypeId.startsWith(it) }
                }
            }
        Timber.i("Active profile \"${profile.name}\" shows a headphone field: $wanted")
        setHoldingClient(wanted)
    }

    @Synchronized
    private fun setHoldingClient(wanted: Boolean) {
        if (wanted == holdsClient) return
        if (wanted) client.start() else client.stop()
        holdsClient = wanted
    }

    /**
     * A controller button. The rider may have assigned it on a profile that shows no headphone
     * field, in which case nothing has asked for a connection yet, so this borrows one.
     */
    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_TOGGLE_NOISE_CANCELLING) return
        scope.launch {
            val borrowed = !holdsClient
            if (borrowed) client.start()
            try {
                val link =
                    withTimeoutOrNull(BORROW_TIMEOUT_MS) {
                        client.state.first { it.link.isSettled() }
                    }?.link
                if (link != HeadphonesLink.Connected) {
                    Timber.w("Nothing to toggle; the headset link is $link")
                    return@launch
                }
                val next =
                    if (client.state.value.noiseCancelling is NoiseCancelling.Cancelling) {
                        NoiseCancelling.Off
                    } else {
                        NoiseCancelling.Cancelling
                    }
                if (!client.setNoiseCancelling(next)) {
                    Timber.w("The headset did not acknowledge the noise cancelling change")
                }
            } finally {
                // The client lingers after the last hold is released, so a second press of the button
                // does not pay for the connection again.
                if (borrowed) client.stop()
            }
        }
    }

    override fun onDestroy() {
        Timber.i("Extension service destroyed")
        scope.cancel()
        releaseClient()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        super.onDestroy()
    }

    /**
     * Whether a borrow can stop waiting.
     *
     * [HeadphonesLink.Idle] is not one of these: it is what the state says before the connection has
     * even been asked for, so treating it as an answer would make the first press of the button do
     * nothing at all while later ones worked.
     */
    internal fun HeadphonesLink.isSettled(): Boolean =
        when (this) {
            HeadphonesLink.Connected,
            HeadphonesLink.NoPermission,
            HeadphonesLink.BluetoothOff -> true
            HeadphonesLink.Idle,
            HeadphonesLink.Searching,
            HeadphonesLink.Connecting -> false
        }

    @Synchronized
    private fun releaseClient() {
        if (holdsClient) client.stop()
        holdsClient = false
    }

    companion object {
        /** Must match `id` in res/xml/extension_info.xml. */
        const val EXTENSION_ID = "xm-headphones"

        /** Must match `actionId` in res/xml/extension_info.xml. */
        const val ACTION_TOGGLE_NOISE_CANCELLING = "toggle-noise-cancelling"

        private const val BORROW_TIMEOUT_MS = 10_000L
    }
}

/**
 * Whether a link is one a borrow can act on.
 *
 * [HeadphonesLink.Idle] is not: it is what the state says before a connection has even been asked
 * for, and the action reads it immediately after asking, so treating it as an answer would end the
 * first press of the button at once while later presses worked.
 */
internal fun HeadphonesLink.isSettled(): Boolean =
    when (this) {
        HeadphonesLink.Connected,
        HeadphonesLink.NoPermission,
        HeadphonesLink.BluetoothOff -> true
        HeadphonesLink.Idle,
        HeadphonesLink.Searching,
        HeadphonesLink.Connecting -> false
    }
