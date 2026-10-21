package fi.nikosavola.karoo.xmheadphones

import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.RfcommTransport
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
 * The extension Karoo binds to for as long as it is installed.
 *
 * The headset link is held only while the active profile shows one of its fields, so a rider
 * with no headphone field reaches no headset and Karoo keeps the Bluetooth radio for whatever
 * else wants it. The toggle action's button borrows a link just while it needs one.
 */
class XmHeadphonesExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    private lateinit var karooSystem: KarooSystemService
    private lateinit var client: XmHeadphonesClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [setHoldingClient] and [releaseClient] guard this against a racing teardown, but the bonus
     * action reads it from a coroutine without either lock, so it has to be visible on its own.
     */
    @Volatile private var holdsClient = false

    /** Whether the radio has been asked for and not yet given back. */
    private var radioHeld = false

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
            // The ask goes out with the first hold rather than with the service, so a rider whose
            // profile shows no headphone field never keeps the radio up for this extension.
            if (!connected) holdRadio(false)
        }
        karooSystem.addConsumer<ActiveRideProfile> { event -> onActiveProfile(event.profile) }
        scope.launch { alertOnDisconnect() }
    }

    /**
     * The silence of a mid-ride drop hides whether the headset or the music stopped.
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
        holdRadio(wanted)
    }

    /**
     * Asks Karoo to keep the Bluetooth radio up, and gives it back when nothing here needs it.
     *
     * Paired rather than repeated, so a rider toggling fields or pressing the action does not stack
     * requests that nothing releases.
     */
    @Synchronized
    private fun holdRadio(wanted: Boolean) {
        if (wanted == radioHeld) return
        karooSystem.dispatch(
            if (wanted) RequestBluetooth(extension) else ReleaseBluetooth(extension),
        )
        radioHeld = wanted
    }

    /**
     * With no headphone field on the profile, nothing has asked for a connection. This
     * borrows one.
     */
    override fun onBonusAction(actionId: String) {
        if (actionId != ACTION_TOGGLE_NOISE_CANCELLING) return
        scope.launch {
            val borrowed = !holdsClient
            if (borrowed) {
                client.start()
                holdRadio(true)
            }
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
                // The client lingers after the last hold, so a second press avoids reconnecting.
                if (borrowed) {
                    client.stop()
                    // A profile that holds one keeps the radio with its own request.
                    if (!holdsClient) holdRadio(false)
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.i("Extension service destroyed")
        scope.cancel()
        releaseClient()
        holdRadio(false)
        karooSystem.disconnect()
        super.onDestroy()
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

        /**
         * How long a press waits for a link it had to borrow.
         *
         * Derived rather than chosen. A connect can take [RfcommTransport.worstCaseConnectMillis]
         * before it gives up, and the finally block above stops the client, which cancels one still
         * in flight. A shorter wait than that would kill the very attempt the press started, so
         * every press on a slow headset would fail while the same wait from a ride profile worked.
         */
        internal val BORROW_TIMEOUT_MS: Long =
            RfcommTransport.worstCaseConnectMillis + BORROW_SLACK_MS

        /** Enough for the client to notice the link and settle the state it publishes. */
        private const val BORROW_SLACK_MS = 3_000L
    }
}

/**
 * Whether a link is one a borrow can act on.
 *
 * [HeadphonesLink.Idle] is the pre-ask state, read right after the ask. Treating it as
 * settled would kill the first press while later ones work.
 */
internal fun HeadphonesLink.isSettled(): Boolean =
    when (this) {
        HeadphonesLink.Connected,
        HeadphonesLink.NoPermission,
        HeadphonesLink.BluetoothOff,
        // Nothing for a borrow to act on, and nothing worth holding the radio open for. This state
        // can be a pairing poll stale, so a press made in that window, just after pairing a headset,
        // is given up on rather than waited on. The other way round, every press with nothing paired
        // would sit out the whole borrow timeout to do nothing.
        HeadphonesLink.NoPairedHeadset -> true
        HeadphonesLink.Idle,
        HeadphonesLink.Searching,
        HeadphonesLink.Connecting -> false
    }
