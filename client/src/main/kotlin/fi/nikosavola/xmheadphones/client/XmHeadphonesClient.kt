package fi.nikosavola.xmheadphones.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import fi.nikosavola.xmheadphones.protocol.DeviceInfoQuestion
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.SonyDeviceInfo
import fi.nikosavola.xmheadphones.protocol.SonyRequest
import fi.nikosavola.xmheadphones.protocol.SonyResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
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

    /** Cut a wait short, so a caller that has just asked for something does not wait it out. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

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
     * How often a paired headset that is carrying no audio is looked at again.
     *
     * The check is a binder call, so it costs nothing on the radio. It is short because a rider
     * waits for the headset's battery once they switch it on, and long enough that a ride with the
     * headset off is not a wakeup a second. A caller with a battery to spare can shorten it.
     */
    @Volatile
    public var absentProbeMillis: Long = DEFAULT_ABSENT_PROBE_MS

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

    init {
        // The default transport asks the platform whether a paired device is carrying audio, and
        // that question needs a context. A caller's own transport answers for itself.
        if (transport === RfcommTransport) RfcommTransport.attach(context)
    }

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

    /**
     * Asks the loop to try now rather than finish the wait it is in.
     *
     * A wait after a failure can be minutes long, and a rider who has just pressed a button is not
     * waiting that long for the answer. A wake that arrives when nothing is waiting is spent on the
     * next wait, which costs one attempt nobody asked for and nothing else.
     */
    public fun wake() {
        wake.trySend(Unit)
    }

    /** A sleep that [wake] can cut short. */
    private suspend fun pause(millis: Long) {
        withTimeoutOrNull(millis) { wake.receive() }
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

    @SuppressLint("MissingPermission") // awaitRadio checked the permission before the list is read
    private suspend fun runForever() {
        var failedAttempts = 0
        // Counted from the start rather than from zero, so a client that has just begun does not
        // open a channel before its first probe has anything to say.
        var lastOpenedAt = SystemClock.elapsedRealtime()
        // The address the last attempt was for. A failure belongs to the headset that failed: it
        // stays on screen while that headset is retried, which is what tells a rider why nothing is
        // happening. The moment a different one is tried it goes, or a rider with two paired
        // headsets reads the first one's failure as the second one's, the one they are waiting on.
        var lastAttempted: String? = null
        while (true) {
            val adapter = awaitRadio() ?: continue
            val bonded = runCatching { adapter.bondedDevices }
            val device = awaitCandidate(bonded) ?: continue
            // Before the attempt is announced, so nothing can read a Connecting link beside the
            // failure of the headset that is not the one being connected to.
            if (lastAttempted != null && lastAttempted != device.address) {
                _state.update { it.copy(lastError = null) }
            }
            lastAttempted = device.address
            val sinceAttempt = SystemClock.elapsedRealtime() - lastOpenedAt
            if (awaitAudio(device, sinceAttempt)) {
                // Only the device that has been failing gives up its backoff. Another device's
                // failures are not this one's, and clearing them here would keep a second paired
                // device on a one second retry cycle for as long as the third one stays off.
                failedAttempts = 0
                continue
            }
            lastOpenedAt = SystemClock.elapsedRealtime()
            setLink(HeadphonesLink.Connecting)
            val connected = runSession(device)
            // Only a device that would not connect is passed over. One that did and then dropped is
            // the one the rider is wearing, so the next attempt goes back to it.
            if (connected) {
                failedAttempts = 0
            } else {
                candidateIndex++
            }
            // The battery stays: it was true a moment ago, and a screen showing it beats a blank one.
            // Everything else only the headset can answer for.
            _state.update { it.copy(link = HeadphonesLink.Searching) }
            // A dropped link is worth getting back promptly, so the first attempts after one stay a
            // second apart. A headset that stays away is another matter: the gap doubles from there
            // to [MAX_RETRY_DELAY_MS], which is what keeps a walk out of range from costing the
            // radio a connection attempt every second for the rest of the ride.
            failedAttempts++
            pause(retryDelayMillis(failedAttempts))
        }
    }

    /**
     * The adapter to try, or null after showing why there is nothing to try and waiting a poll.
     *
     * A missing adapter and one that is off read the same to a rider: this app cannot reach the
     * radio.
     */
    @SuppressLint("MissingPermission") // hasPermissions is the first thing this checks
    private suspend fun awaitRadio(): BluetoothAdapter? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (!hasPermissions()) {
            setLink(HeadphonesLink.NoPermission)
            pause(PRECONDITION_POLL_MS)
            return null
        }
        if (adapter == null || !adapter.isEnabled) {
            setLink(HeadphonesLink.BluetoothOff)
            pause(PRECONDITION_POLL_MS)
            return null
        }
        return adapter
    }

    /**
     * The paired device to try, or null after showing why there is none and waiting a poll.
     *
     * Nothing paired looks like a headset. There is no scan that could find one: the control channel
     * only exists once the headset is paired, and pairing is the rider's own step in Karoo's
     * Bluetooth settings. So this waits for one to appear.
     */
    private suspend fun awaitCandidate(bonded: Result<Set<BluetoothDevice>>): BluetoothDevice? {
        val device = bonded.getOrNull()?.let { nextCandidate(it) }
        if (device != null) return device
        // Reading the list can fail on its own, with the permission going away while this loop runs.
        // That is not a list that turned out empty, and telling a rider to pair a headset they have
        // already paired is worse than saying the link is still looking.
        if (bonded.isSuccess) {
            setLink(HeadphonesLink.NoPairedHeadset)
        } else {
            Timber.w("Could not read the paired devices: ${bonded.exceptionOrNull()?.message}")
            setLink(HeadphonesLink.Searching)
        }
        pause(PAIRING_POLL_MS)
        return null
    }

    /**
     * Whether to pass [device] over and wait, because its audio link is not up.
     *
     * A headset that is off, asleep or out of range cannot answer, and opening a channel to it costs
     * the radio the whole connect timeout rather than the microsecond this check takes. The rider's
     * music needs the audio link anyway, so a headset that is in use has it and one that does not is
     * not going to answer. A ride spent with the headset switched off must not page the radio on a
     * one second cycle for hours.
     *
     * A headset can also be on and answering while its audio goes elsewhere, so the guess is given
     * one real attempt every [GATE_REOPEN_MS]. Without that, a wrong "no" would lock a headset out
     * for the whole ride with nothing able to correct it.
     */
    private suspend fun awaitAudio(device: BluetoothDevice, sinceAttempt: Long): Boolean {
        if (carryingAudio(device) != false || sinceAttempt >= GATE_REOPEN_MS) return false
        Timber.d("${device.address} carries no audio; waiting for it to come back")
        setLink(HeadphonesLink.Searching)
        _state.update { it.copy(lastError = HeadsetProblem.NotConnected) }
        // The other paired devices get their turn: one that is on could be among them.
        candidateIndex++
        pause(absentProbeMillis.coerceAtLeast(MIN_ABSENT_PROBE_MS))
        return true
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
        transportAnswer("whether ${device.address} is reachable") {
            factory.looksReachable(device)
        } ?: false

    /**
     * Whether the audio link to [device] is up, or null when nothing here can say.
     *
     * A transport that throws is a broken one rather than a headset that is not there, so the
     * answer that keeps the loop honest is that nobody knows.
     */
    private fun carryingAudio(device: BluetoothDevice): Boolean? =
        transportAnswer("whether ${device.address} carries audio") { transport.carriesAudio(device) }

    /**
     * Asks the transport [question], or null when it fails to answer.
     *
     * A transport that throws is a broken one rather than a headset that is not there, and this is
     * where a caller's own transport meets a loop that has to keep running whatever it does. Losing
     * the loop to it would leave the link saying whatever it last said, with nothing looking again.
     */
    private inline fun <T> transportAnswer(question: String, ask: () -> T): T? =
        runCatching(ask)
            .onFailure { Timber.w("The transport could not tell $question: ${it.message}") }
            .getOrNull()

    /**
     * Why a paired device could not be opened.
     *
     * The exception is the same whether the headset is asleep or is not a Sony one, so it cannot say
     * which. What can is whether the device already carries audio on this phone: a headset that is
     * on and in reach is connected for sound, so a channel that will not open means it does not run
     * Sony's protocol rather than that it is switched off. A device nobody can tell about gets the
     * gentler of the two, since telling a rider to switch a headset on costs them a moment while
     * telling them it is the wrong make sends them looking for another one.
     *
     * Asked through the transport, which is the only thing that knows how to ask on a given
     * platform. Asked of the platform directly, this answered "not connected" for every device on
     * the Karoo, which refuses the per-device profile query.
     */
    private fun unopened(device: BluetoothDevice): HeadsetProblem =
        if (carryingAudio(device) == true) HeadsetProblem.NotSony else HeadsetProblem.NotConnected

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

        /** The default for [absentProbeMillis]. */
        public const val DEFAULT_ABSENT_PROBE_MS: Long = 15_000L

        /** A probe interval below this would be a loop, so a caller asking for one gets this. */
        private const val MIN_ABSENT_PROBE_MS = 1_000L

        /**
         * How long a headset that carries no audio is left alone before one real attempt.
         *
         * Sony's control channel does not need the audio link, so a headset can be on and answering
         * with its sound going to a phone. The check is a guess and this is what stops a wrong one
         * from locking the headset out for the ride.
         */
        private const val GATE_REOPEN_MS = 300_000L

        private const val RETRY_DELAY_MS = 1_000L

        /** How many attempts after a failure stay at [RETRY_DELAY_MS] before the gap starts to grow. */
        private const val QUICK_RETRIES = 4

        /** A headset that has been away this long is looked for once every five minutes. */
        private const val MAX_RETRY_DELAY_MS = 300_000L

        /** [RETRY_DELAY_MS] for the first [QUICK_RETRIES], then doubling up to [MAX_RETRY_DELAY_MS]. */
        internal fun retryDelayMillis(failedAttempts: Int): Long =
            if (failedAttempts <= QUICK_RETRIES) {
                RETRY_DELAY_MS
            } else {
                (RETRY_DELAY_MS shl (failedAttempts - QUICK_RETRIES - 1).coerceIn(0, 16))
                    .coerceAtMost(MAX_RETRY_DELAY_MS)
            }

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
