package fi.nikosavola.xmheadphones.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import fi.nikosavola.xmheadphones.protocol.DeviceInfoQuestion
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Talks to a Sony headset over BLE: finds one, keeps the link, and exposes what it reports as
 * [state] while offering its controls as suspend functions.
 *
 * One instance per process. The connection is reference counted so that the extension service and a
 * settings screen can both hold it, and it lingers after the last of them lets go, because getting
 * the link back costs the rider a wait for the headset to accept a connection again.
 *
 * Nothing here connects on its own: [start] has to be called, and every command returns false rather
 * than throwing when there is no link, so a caller that is merely early does not have to handle an
 * exception.
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

    /** Address of the headset this client is locked to, or null until one has been found. */
    public val savedAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)

    /**
     * Asks for a connection. Reference counted, so the caller that starts twice has to stop twice.
     *
     * The permission check happens here rather than in a caller: without it nothing can be scanned,
     * and a rider who denied the permission should see that state rather than a search that never
     * ends.
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
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && job != null) {
            Timber.i("Last hold released; stopping in ${STOP_LINGER_MS / 1000} s")
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(STOP_LINGER_MS)
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
        _state.value = XmHeadphonesState()
    }

    /** Drops the saved headset so the next one put into pairing mode is used instead. */
    @Synchronized
    public fun forget() {
        Timber.i("Forgetting headset ${savedAddress}")
        prefs.edit().remove(KEY_ADDRESS).apply()
        val previous = job ?: return
        activeSession = null
        _state.value = XmHeadphonesState()
        // The cancelled loop clears activeSession again as it unwinds, so the next one waits for it
        // rather than racing it. Without that, the stale cleanup can null the new session and every
        // command goes nowhere until the following reconnect.
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

    private suspend fun runForever() {
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
            try {
                setLink(HeadphonesLink.Searching)
                val device = scan(adapter)
                setLink(HeadphonesLink.Connecting)
                runSession(device)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Timber.w("Connection ended: ${failure.message}")
                _state.update { it.copy(lastError = failure.message) }
            }
            // The battery is kept: it was true a moment ago and a screen showing it is better than a
            // blank one. Everything else only the headset can answer for.
            _state.update { it.copy(link = HeadphonesLink.Searching) }
            delay(RETRY_DELAY_MS)
        }
    }

    private suspend fun runSession(device: BluetoothDevice) {
        val session = SonySession(context, device)
        val collector = scope.launch {
            session.responses.collect { response -> apply(response) }
        }
        activeSession = session
        try {
            coroutineScope {
                val running = launch { session.run() }
                session.handshaken.await()
                prefs.edit().putString(KEY_ADDRESS, device.address).apply()
                Timber.i("Connected to ${device.address}")
                _state.update {
                    it.copy(
                        link = HeadphonesLink.Connected,
                        capabilities = session.capabilities.value,
                        lastError = null,
                    )
                }
                running.join()
            }
        } finally {
            activeSession = null
            collector.cancel()
            session.close()
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

    private fun hasPermissions(): Boolean {
        val needed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PERMISSIONS_S else PERMISSIONS_LEGACY
        return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    // region scanning

    /** Suspends until a headset is seen advertising, preferring the one we were talking to before. */
    @SuppressLint("MissingPermission") // hasPermissions ran first
    private suspend fun scan(adapter: BluetoothAdapter): BluetoothDevice {
        val address = savedAddress
        val filter =
            if (address != null) {
                ScanFilter.Builder().setDeviceAddress(address).build()
            } else {
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SonyGatt.SERVICE))
                    .build()
            }
        Timber.i(if (address != null) "Looking for $address" else "Looking for any Sony headset")
        while (true) {
            // Android quietly demotes a scan that runs longer than half an hour; restarting avoids it.
            val device = withTimeoutOrNull(SCAN_RESTART_MS) { scanOnce(adapter, filter) }
            if (device != null) return device
            Timber.d("Still looking for the headset")
        }
    }

    @SuppressLint("MissingPermission") // hasPermissions ran first
    private suspend fun scanOnce(
        adapter: BluetoothAdapter,
        filter: ScanFilter,
    ): BluetoothDevice = suspendCancellableCoroutine { continuation ->
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            continuation.resumeWithException(IOException("Bluetooth is off"))
            return@suspendCancellableCoroutine
        }
        val callback =
            object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!continuation.isActive) return
                    Timber.i(
                        "Found ${result.device.address} " +
                            "rssi=${result.rssi} name=${result.scanRecord?.deviceName}",
                    )
                    runCatching { scanner.stopScan(this) }
                    continuation.resume(result.device)
                }

                override fun onScanFailed(errorCode: Int) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(IOException("Scan failed, error $errorCode"))
                }
            }
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
        continuation.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
    }

    // endregion

    public companion object {
        private const val PREFERENCES = "sony-headphones"
        private const val KEY_ADDRESS = "headset_address"

        private const val PRECONDITION_POLL_MS = 5_000L
        private const val RETRY_DELAY_MS = 1_000L
        private const val STOP_LINGER_MS = 120_000L
        private const val SCAN_RESTART_MS = 10 * 60_000L

        private val PERMISSIONS_S =
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        private val PERMISSIONS_LEGACY = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        @Volatile private var instance: XmHeadphonesClient? = null

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
