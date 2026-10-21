package fi.nikosavola.xmheadphones.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import fi.nikosavola.xmheadphones.protocol.DeviceInfoQuestion
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.SonyDeviceInfo
import fi.nikosavola.xmheadphones.protocol.SonyRequest
import fi.nikosavola.xmheadphones.protocol.SonyResponse
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Talks to a Sony headset: finds the paired one, keeps the link, and exposes what it reports as
 * [state] with its controls as suspend functions.
 *
 * The control channel is a classic Bluetooth serial profile, so a headset has to be paired before
 * this can reach it at all. Pairing is the rider's own step, done in Karoo's Bluetooth settings,
 * which is also how their audio gets there.
 *
 * One instance per process. The connection is reference counted, so the extension service and a
 * settings screen can hold it at the same time. It lingers briefly after the last hold goes, because
 * getting the link back makes the rider wait for the headset to accept a connection again.
 *
 * Nothing connects on its own. [start] has to be called, and every command returns false instead of
 * throwing when there is no link, so a caller that is early needs no exception handling.
 */
public class XmHeadphonesClient private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(XmHeadphonesState())
    public val state: StateFlow<XmHeadphonesState> = _state.asStateFlow()

    @Volatile private var activeSession: SonySession? = null
    private var job: Job? = null
    private var lingerJob: Job? = null
    private var users = 0

    /** Which candidate the next attempt takes, so a device that will not answer is not retried alone. */
    @Volatile private var candidateIndex = 0

    /** Whether a headset ever completed a handshake since the last [start]. */
    @Volatile private var hasConnected = false

    /**
     * How long a link that has dropped stays up after the last hold is released.
     *
     * Only for a headset that was reached at least once: with none ever reached, [stop] shuts down at
     * once rather than sitting on the radio, so a caller that has just given up gets what it asked
     * for. A watch should still ask for a shorter spell than the default.
     */
    @Volatile
    public var lingerMillis: Long = DEFAULT_LINGER_MS

    /**
     * How the bytes reach the headset.
     *
     * The classic serial profile, unless a caller has another way to the same headset. A test sets
     * this to one that answers in the protocol's own bytes, and a platform without classic Bluetooth
     * sets it to whatever it does have. It brings its own way of telling one paired device from
     * another, so nothing outside it knows which profile is in use. Read when the next connection is
     * opened, so it is safe to set while one is up.
     */
    @Volatile
    public var transport: HeadsetTransportFactory = RfcommTransport

    /** Address of the headset this client is locked to, or null until one has been found. */
    public val savedAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)

    /**
     * Asks for a connection. Reference counted, so a caller that starts twice has to stop twice.
     *
     * The permission check lives in here rather than in a caller. Without it no paired device can be
     * read and no channel opened, and a rider who denied the permission should see that state rather
     * than a search that never ends.
     */
    @Synchronized
    public fun start() {
        users++
        lingerJob?.cancel()
        lingerJob = null
        if (job?.isActive != true) {
            Timber.i("Starting the headset connection")
            job = scope.launch { runForever() }
        }
    }

    /**
     * Gives up one hold on the connection. The last one to let go leaves the link up for a while, so
     * closing a settings screen does not drop a headset the rider is still using.
     */
    @Synchronized
    public fun stop() {
        if (users == 0) {
            // Nothing was held, so there is nothing to give up and no reason to keep the link alive.
            Timber.d("Stop without a matching start")
            return
        }
        users--
        if (users == 0 && job != null) {
            if (!hasConnected) {
                // Nothing ever connected, so there is no link worth keeping warm. Lingering would
                // leave the loop looking for a headset with nobody holding it, which is the last
                // thing a caller that has just given up wants on a battery.
                Timber.i("Last hold released with no headset ever connected; stopping now")
                shutDown()
                return
            }
            val linger = lingerMillis
            Timber.i("Last hold released; stopping in ${linger / 1000} s")
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(linger)
                shutDown()
            }
        }
    }

    @Synchronized
    private fun shutDown() {
        if (users > 0) return
        Timber.i("Stopping the headset connection")
        job?.cancel()
        job = null
        hasConnected = false
        _state.value = XmHeadphonesState()
    }

    /** Drops the saved headset, so the next connection is whichever paired one answers first. */
    @Synchronized
    public fun forget() {
        Timber.i("Forgetting headset ${savedAddress}")
        prefs.edit().remove(KEY_ADDRESS).apply()
        candidateIndex = 0
        val previous = job ?: return
        activeSession = null
        _state.value = XmHeadphonesState()
        // The cancelled loop clears activeSession again as it unwinds, so the next one waits for that
        // instead of racing it. Without this, the stale cleanup can null the new session, and every
        // command goes nowhere until the next reconnect.
        job = scope.launch {
            previous.cancelAndJoin()
            runForever()
        }
    }

    /** Turns noise cancelling off, on, or into ambient sound. False when there is no link. */
    public suspend fun setNoiseCancelling(noiseCancelling: NoiseCancelling): Boolean =
        activeSession?.setNoiseCancelling(noiseCancelling) ?: false

    public suspend fun setEqualizerPreset(preset: EqualizerPreset): Boolean =
        activeSession?.send(SonyRequest.SetEqualizer(preset))
            ?: false

    /** Asks the headset for its battery and noise control again, rather than waiting for a change. */
    public suspend fun refresh() {
        val session = activeSession ?: return
        session.readBattery()
        session.readNoiseCancelling()
    }

    @SuppressLint("MissingPermission") // hasPermissions ran at the top of the loop
    private suspend fun runForever() {
        var failedAttempts = 0
        // The address the last attempt was for. A failure belongs to the headset that failed: it
        // stays on screen while that headset is retried, which is what tells a rider why nothing is
        // happening. The moment a different one is tried it goes, or a rider with two paired
        // headsets reads the first one's failure as the second one's, the one they are waiting on.
        var lastAttempted: String? = null
        while (true) {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (!hasPermissions()) {
                setLink(HeadphonesLink.NoPermission)
                delay(PRECONDITION_POLL_MS)
                continue
            }
            if (adapter == null || !adapter.isEnabled) {
                setLink(HeadphonesLink.BluetoothOff)
                delay(PRECONDITION_POLL_MS)
                continue
            }
            val bonded = runCatching { adapter.bondedDevices }
            val device = bonded.getOrNull()?.let { nextCandidate(it) }
            if (device == null) {
                // Nothing paired looks like a headset. There is no scan that could find one: the
                // control channel only exists once the headset is paired, and pairing is the rider's
                // own step in Karoo's Bluetooth settings. So this waits for one to appear.
                //
                // Reading the list can fail on its own, with the permission going away while this
                // loop runs. That is not a list that turned out empty, and telling a rider to pair a
                // headset they have already paired is worse than saying the link is still looking.
                if (bonded.isSuccess) {
                    setLink(HeadphonesLink.NoPairedHeadset)
                } else {
                    Timber.w("Could not read the paired devices: ${bonded.exceptionOrNull()?.message}")
                    setLink(HeadphonesLink.Searching)
                }
                delay(PAIRING_POLL_MS)
                continue
            }
            // Before the attempt is announced, so nothing can read a Connecting link beside the
            // failure of the headset that is not the one being connected to.
            if (lastAttempted != null && lastAttempted != device.address) {
                _state.update { it.copy(lastError = null) }
            }
            lastAttempted = device.address
            setLink(HeadphonesLink.Connecting)
            val connected =
                try {
                    runSession(device)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Timber.w("Connection ended: ${failure.message}")
                    _state.update {
                        it.copy(
                            lastError =
                                HeadsetProblem.Platform(failure.message ?: "the connection failed"),
                        )
                    }
                    false
                }
            // Only a device that would not connect is passed over. One that did and then dropped is
            // the one the rider is wearing, so the next attempt goes back to it.
            if (!connected) candidateIndex++
            // The battery stays: it was true a moment ago, and a screen showing it beats a blank one.
            // Everything else only the headset can answer for.
            _state.update { it.copy(link = HeadphonesLink.Searching) }
            // Without a growing gap, a headset that answers but refuses the connection is retried
            // every second for as long as anything holds this client, with the radio on throughout.
            // Once something has connected the gap stays short, because a dropped link is worth
            // getting back promptly.
            failedAttempts = if (hasConnected) 0 else failedAttempts + 1
            delay(if (failedAttempts == 0) RETRY_DELAY_MS else retryDelayMillis(failedAttempts))
        }
    }

    /**
     * The paired device to try out of [bonded], or null when none of them could be one.
     *
     * A device the headset was last reached on goes first, since that is the one the rider is using.
     * [candidateIndex] is what passes over one that will not answer, so a rider with two headsets
     * reaches the second rather than retrying the first forever.
     */
    private fun nextCandidate(bonded: Set<BluetoothDevice>): BluetoothDevice? {
        val saved = savedAddress
        val factory = transport
        val candidates =
            bonded.sortedByDescending { device ->
                when {
                    device.address == saved -> 2
                    // Whatever the transport can recognise its peer by. A device it cannot tell about
                    // is still tried: its services were never recorded, which says nothing about
                    // whether it runs one, and a wrong guess costs one failed attempt.
                    reachable(factory, device) -> 1
                    else -> 0
                }
            }
        if (candidates.isEmpty()) {
            candidateIndex = 0
            return null
        }
        return candidates[candidateIndex % candidates.size]
    }

    /**
     * Whether the transport recognises [device], which is only ever a hint about the order to try
     * them in.
     *
     * A transport that throws is a broken one rather than a headset that is not there, and this is
     * where a caller's own transport meets a loop that has to keep running whatever it does. Losing
     * the loop to it would leave the link saying whatever it last said, with nothing looking again.
     */
    private fun reachable(factory: HeadsetTransportFactory, device: BluetoothDevice): Boolean =
        runCatching { factory.looksReachable(device) }
            .onFailure {
                Timber.w("The transport could not tell whether ${device.address} is reachable: ${it.message}")
            }
            .getOrDefault(false)

    /**
     * Why a paired device could not be opened.
     *
     * The exception is the same whether the headset is asleep or is not a Sony one, so it cannot say
     * which. What can is whether the device already carries audio on this phone: a headset that is
     * on and in reach is connected for sound, so a channel that will not open means it does not run
     * Sony's protocol rather than that it is switched off.
     */
    @SuppressLint("MissingPermission") // hasPermissions ran at the top of the loop
    private fun unopened(device: BluetoothDevice): HeadsetProblem {
        val carryingAudio =
            runCatching {
                val manager =
                    context.getSystemService(BluetoothManager::class.java) ?: return@runCatching false
                manager.getConnectionState(device, BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED ||
                    manager.getConnectionState(device, BluetoothProfile.HEADSET) ==
                        BluetoothProfile.STATE_CONNECTED
            }
                .getOrDefault(false)
        return if (carryingAudio) HeadsetProblem.NotSony else HeadsetProblem.NotConnected
    }

    /** False when the headset never answered, which is what moves the search on to the next one. */
    private suspend fun runSession(device: BluetoothDevice): Boolean {
        forgetTheLastHeadsetIfThisIsADifferentOne(device)
        val pipe =
            try {
                transport.open(device)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Timber.w("The headset did not answer: ${failure.message}")
                _state.update { it.copy(lastError = unopened(device)) }
                return false
            }
        val session = SonySession(pipe)
        val collector = scope.launch {
            session.responses.collect { response -> apply(response) }
        }
        activeSession = session
        var connected = false
        try {
            coroutineScope {
                val running = launch { session.run() }
                session.handshaken.await()
                connected = true
                prefs.edit().putString(KEY_ADDRESS, device.address).apply()
                Timber.i("Connected to ${device.address}")
                // Set before the link is published, so a caller that sees Connected and immediately
                // lets go still gets the linger rather than an abrupt shutdown.
                hasConnected = true
                _state.update {
                    it.copy(
                        link = HeadphonesLink.Connected,
                        address = device.address,
                        capabilities = session.capabilities.value,
                        lastError = null,
                    )
                }
                running.join()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // The channel is Sony's, so whatever answered on it is a Sony device. A headset that
            // refuses over a disabled command table, or one that drops mid-session, has its own
            // words for it and they are the only thing that tells the two apart.
            Timber.w("The session ended: ${failure.message}")
            _state.update {
                it.copy(lastError = HeadsetProblem.Platform(failure.message ?: "the link dropped"))
            }
        } finally {
            // Only if this is still the session in use. A caller that comes back after the linger can
            // have started the next one already, and clearing it here would leave the state saying
            // Connected while every command finds no session and does nothing.
            if (activeSession === session) activeSession = null
            collector.cancel()
            session.close()
        }
        return connected
    }

    /**
     * Clears what the last headset reported when this is not that headset.
     *
     * The state keeps what a headset said across a drop on purpose, so a link that goes for a moment
     * comes back showing the last known battery rather than a blank. Carried to a different headset
     * that becomes a lie: one model name beside another's address, and a battery level that belongs
     * to a headset no longer on the link.
     *
     * Before the session runs, not after the handshake. The session reports what it reads as it
     * reads it, so clearing afterwards would throw away the answers it had just collected. The
     * address moves with the clearing for the same reason: it is what tells the next attempt, and
     * the reconnect after this one, which headset the values belong to.
     */
    private fun forgetTheLastHeadsetIfThisIsADifferentOne(device: BluetoothDevice) {
        val address = _state.value.address
        if (address == null || address == device.address) return
        Timber.i("A different headset is on the link; dropping what $address reported")
        _state.update {
            it.copy(
                address = device.address,
                device = SonyDeviceInfo(),
                capabilities = null,
                battery = null,
                noiseCancelling = null,
                equalizerPreset = null,
                lastAmbientLevel = null,
                lastFocusOnVoice = null,
            )
        }
    }

    private fun apply(response: SonyResponse) {
        _state.update { current ->
            when (response) {
                is SonyResponse.DeviceInfo ->
                    current.copy(
                        device =
                            when (response.question) {
                                DeviceInfoQuestion.ModelName ->
                                    current.device.copy(modelName = response.value)
                                DeviceInfoQuestion.FirmwareVersion ->
                                    current.device.copy(firmwareVersion = response.value)
                                else -> current.device
                            },
                    )
                is SonyResponse.Battery -> current.copy(battery = response.reading)
                is SonyResponse.NoiseControl ->
                    current.copy(
                        noiseCancelling = response.state,
                        lastAmbientLevel =
                            (response.state as? NoiseCancelling.Ambient)?.level
                                ?: current.lastAmbientLevel,
                        lastFocusOnVoice =
                            (response.state as? NoiseCancelling.Ambient)?.focusOnVoice
                                ?: current.lastFocusOnVoice,
                    )
                is SonyResponse.Equalizer -> current.copy(equalizerPreset = response.preset)
                is SonyResponse.Acknowledged,
                is SonyResponse.ProtocolInfo,
                is SonyResponse.SupportedFunctions,
                is SonyResponse.Unrecognised -> current
            }
        }
    }

    private fun setLink(link: HeadphonesLink) {
        if (_state.value.link != link) Timber.i("Link ${_state.value.link} -> $link")
        _state.update { it.copy(link = link) }
    }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    public companion object {
        private const val PREFERENCES = "sony-headphones"
        private const val KEY_ADDRESS = "headset_address"

        private const val PRECONDITION_POLL_MS = 5_000L
        private const val PAIRING_POLL_MS = 10_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L

        /** Doubles the gap per failed attempt, up to [MAX_RETRY_DELAY_MS]. */
        private fun retryDelayMillis(failedAttempts: Int): Long =
            (RETRY_DELAY_MS shl (failedAttempts - 1).coerceIn(0, 15)).coerceAtMost(MAX_RETRY_DELAY_MS)

        /** The default for [lingerMillis], sized for a device with a battery to spare. */
        public const val DEFAULT_LINGER_MS: Long = 120_000L

        /**
         * What a caller has to hold before [start] does anything useful, on [sdkInt].
         *
         * Android 12 put reading the paired devices, which is what picking the headset out of them
         * needs, behind `BLUETOOTH_CONNECT`. Before that the platform's own `BLUETOOTH` and
         * `BLUETOOTH_ADMIN` cover it, and both are granted at install, so nothing is asked for at
         * runtime. That is why the older branch is empty rather than a leftover.
         *
         * The list is public so a caller asks for it instead of keeping a copy. A copy that drifts
         * leaves the rider on a permission prompt that never satisfies the connection. Each name
         * also has to be declared in the caller's own manifest, and a request for one that is not
         * is ignored without an error.
         */
        // The names are compile-time String constants, so naming them below API 31 costs nothing at
        // runtime. Lint sees the fields they come from and cannot tell.
        @SuppressLint("InlinedApi")
        public fun requiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
            if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT)
            else emptyList()

        // The application context, not an activity's: this is the process-wide client, and its
        // lifetime is the process's by design.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: XmHeadphonesClient? = null

        /** One client per process, shared by whatever needs the headset. */
        public fun get(context: Context): XmHeadphonesClient =
            instance
                ?: synchronized(this) {
                    instance
                        ?: XmHeadphonesClient(context.applicationContext).also { instance = it }
                }

        /**
         * Drops the process-wide instance and its scope.
         *
         * For tests only: the instance is static, so a suite that connects to a headset would leave it
         * running for the next test, and Robolectric keeps statics across tests in a class.
         */
        public fun resetForTests() {
            synchronized(this) {
                instance?.scope?.cancel()
                instance = null
            }
        }
    }
}
