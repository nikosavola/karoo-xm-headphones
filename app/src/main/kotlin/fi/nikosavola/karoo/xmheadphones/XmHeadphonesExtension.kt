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

    /**
     * How many things here are holding the radio: the profile, and one per press borrowing it.
     *
     * Counted rather than a flag, because two presses can overlap and the first one finishing must
     * not give the radio back while the second is still waiting on the link it asked for.
     */
    private var radioClaims = 0

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
            //
            // A system that went away took the claim with it, and comes back with nothing held. The
            // profile event that follows does not ask again, since it is the same profile as before,
            // so the claim is taken here or the link the client is running loses its radio.
            if (connected) {
                if (holdsClient) claimRadio()
            } else {
                forgetRadio()
            }
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
        if (wanted) {
            client.start()
            claimRadio()
        } else {
            client.stop()
            dropRadio()
        }
        holdsClient = wanted
    }

    /**
     * Asks Karoo to keep the Bluetooth radio up, the first time anything here needs it.
     *
     * Counted, so the profile and a press can hold it at the same time and the first to finish does
     * not take it from the other. Only the change between none and some is dispatched, so a rider
     * toggling fields or pressing the action does not stack asks that nothing gives back.
     */
    @Synchronized
    private fun claimRadio() {
        if (radioClaims++ == 0) karooSystem.dispatch(RequestBluetooth(extension))
    }

    @Synchronized
    private fun dropRadio() {
        if (radioClaims == 0) return
        if (--radioClaims == 0) karooSystem.dispatch(ReleaseBluetooth(extension))
    }

    /** The system took everything that was claimed with it, so nothing is held until it comes back. */
    @Synchronized
    private fun forgetRadio() {
        radioClaims = 0
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
                claimRadio()
            }
            // A wait after a failure can be minutes long, and the press is a rider asking for the
            // change now. The loop is woken instead of sitting the wait out.
            client.wake()
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
                    dropRadio()
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.i("Extension service destroyed")
        scope.cancel()
        releaseClient()
        forgetRadio()
        karooSystem.disconnect()
        super.onDestroy()
    }

    @Synchronized
    private fun releaseClient() {
        if (holdsClient) {
            client.stop()
            dropRadio()
        }
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
