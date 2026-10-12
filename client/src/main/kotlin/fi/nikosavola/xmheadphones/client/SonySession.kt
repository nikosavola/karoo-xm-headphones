package fi.nikosavola.xmheadphones.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.BatteryReading
import fi.nikosavola.xmheadphones.protocol.DeviceInfoQuestion
import fi.nikosavola.xmheadphones.protocol.MdrDataType
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameCodec
import fi.nikosavola.xmheadphones.protocol.NoiseAdaptiveFields
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolTable
import fi.nikosavola.xmheadphones.protocol.SonyCapabilities
import fi.nikosavola.xmheadphones.protocol.SonyHandshakeRequest
import fi.nikosavola.xmheadphones.protocol.SonyMessageCodec
import fi.nikosavola.xmheadphones.protocol.SonyRequest
import fi.nikosavola.xmheadphones.protocol.SonyResponse
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * One connected headset: the GATT session and the protocol on top of it.
 *
 * Everything the headset says arrives on one characteristic and everything we say goes out on
 * another, so this owns a single loop that decodes incoming frames and a single lock that serialises
 * outgoing ones. The protocol is stop and wait: one frame on the wire at a time, each acknowledged
 * before the next goes out.
 *
 * [run] returns only when the link is gone. Nothing here throws because a headset misbehaved; a frame
 * this client cannot read is logged and acknowledged all the same, because a headset that is not
 * acknowledged keeps resending it.
 */
internal class SonySession(
    private val context: Context,
    private val device: BluetoothDevice,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _capabilities = MutableStateFlow<SonyCapabilities?>(null)
    private val _responses =
        MutableSharedFlow<SonyResponse>(
            extraBufferCapacity = RESPONSE_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** What the headset advertised, once the handshake has settled. */
    val capabilities: StateFlow<SonyCapabilities?> = _capabilities.asStateFlow()

    /**
     * Everything the headset sends that is not an acknowledgement. The oldest is dropped under
     * pressure: these are state reports, and the next poll refreshes whatever is lost.
     */
    val responses: SharedFlow<SonyResponse> = _responses.asSharedFlow()

    /** Completes once the handshake has settled, so a caller can act on a usable connection. */
    val handshaken = CompletableDeferred<Unit>()

    /** Completes when the frame loop ends, which is how a dropped link ends the session. */
    private val finished = CompletableDeferred<Unit>()

    private val incoming = Channel<MdrFrame>(capacity = FRAME_QUEUE)
    private val acknowledgements = Channel<Int>(capacity = FRAME_QUEUE)
    private val assembler = MdrFrameAssembler()
    private val waiters = CopyOnWriteArrayList<ResponseWaiter>()
    private val sendLock = Mutex()
    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()
    private val subscribed = CompletableDeferred<Unit>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var write: BluetoothGattCharacteristic? = null
    @Volatile private var notify: BluetoothGattCharacteristic? = null
    @Volatile private var closed = false
    @Volatile private var adaptive: NoiseAdaptiveFields = NoiseAdaptiveFields.Default

    /**
     * The ambient level the headset last reported, so switching noise cancelling off does not tell it
     * to forget the level while it is off.
     */
    @Volatile private var ambientLevel: AmbientLevel = AmbientLevel.Silent

    /** The sequence the next frame we send carries; the protocol has a two value sequence space. */
    private var dataSequence = 0

    /**
     * Held for the length of one GATT write and nothing else.
     *
     * A write mutates the characteristic it goes through, and there is one characteristic for both
     * commands and acknowledgements, so two writes from different coroutines would otherwise be able
     * to overwrite each other's bytes between setting them and sending them. It is deliberately not
     * [sendLock]: acknowledgements are written from the frame loop, and holding the command lock
     * across that would deadlock against a reply that arrives before its own acknowledgement.
     */
    private val writeLock = Mutex()

    /**
     * A response the session is waiting for.
     *
     * [expectedReplyCommand] is the command byte the headset answers with, which is the request's own
     * command plus one for every GET in this protocol. Matching on the response type alone would let a
     * device-initiated notification satisfy a pending request, and those decode to the same type.
     */
    private class ResponseWaiter(
        val expectedReplyCommand: Byte,
        val matches: (SonyResponse) -> Boolean,
    ) {
        val channel = Channel<SonyResponse>(capacity = 1)
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Timber.d("Connection state status=$status newState=$newState")
                when {
                    newState == BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        // Ends run()'s wait, or the frame loop if the link was already up.
                        connected.completeExceptionally(IOException("disconnected, status $status"))
                        servicesDiscovered.completeExceptionally(
                            IOException("disconnected, status $status"),
                        )
                        incoming.close()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
                else {
                    servicesDiscovered.completeExceptionally(
                        IOException("service discovery failed, $status"),
                    )
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) subscribed.complete(Unit)
                else subscribed.completeExceptionally(IOException("subscribe failed, $status"))
            }

            // Both variants are overridden because which one runs depends on the platform: API 33
            // and up call this one with the value, and older releases, Karoo 2 among them, call the
            // one below it and leave the value on the characteristic.
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                onNotification(value)
            }

            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                // Not on API 33 and up: there the framework routes the value-carrying variant into
                // this one by default, so handling both would double every notification.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                onNotification(characteristic.value?.copyOf() ?: return)
            }

        }

    /**
     * Connects, subscribes, shakes hands and then serves requests until the link drops.
     *
     * Throws only when the headset never turned up; a link that goes away later returns normally.
     */
    @SuppressLint("MissingPermission") // the caller checks the permission before connecting
    @Suppress("DEPRECATION") // the transport-carrying connectGatt overload is API 23+
    suspend fun run() {
        val connection =
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("connectGatt returned null")
        gatt = connection
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() }
                ?: throw IOException("timed out connecting to ${device.address}")
            if (!connection.discoverServices()) throw IOException("discoverServices refused")
            withTimeoutOrNull(GATT_TIMEOUT_MS) { servicesDiscovered.await() }
                ?: throw IOException("timed out discovering services")

            val service =
                connection.getService(SonyGatt.SERVICE)
                    ?: throw IOException(
                        "no control service on the headset; it has " +
                            connection.services.map { it.uuid },
                    )
            write =
                service.getCharacteristic(SonyGatt.WRITE)
                    ?: throw IOException("no write characteristic")
            notify =
                service.getCharacteristic(SonyGatt.NOTIFY)
                    ?: throw IOException("no notify characteristic")

            subscribe()

            scope.launch { processIncoming() }
            handshake()
            // The poll loop keeps the battery fresh while this waits for the link to end, which is
            // what the frame loop finishing means.
            scope.launch { poll() }
            finished.await()
            Timber.i("The link is gone")
        } finally {
            close()
        }
    }

    @SuppressLint("MissingPermission") // the caller checks the permission before connecting
    override fun close() {
        closed = true
        scope.cancel()
        incoming.close()
        gatt?.let {
            // Both, always: a BluetoothGatt left open is what causes the status 133 that follows a
            // later connect attempt on the same device.
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    // region commands

    /**
     * Sends [request] and waits for the headset's acknowledgement.
     *
     * False means the headset never acknowledged, which is the only honest answer available: this is
     * a slow link, and a frame that was written is not a frame that was received.
     */
    suspend fun send(request: SonyRequest): Boolean {
        val capabilities = _capabilities.value ?: return false
        if (!request.supportedBy(capabilities)) {
            Timber.i("Not sending ${request::class.simpleName}: the headset does not advertise it")
            return false
        }
        return sendLock.withLock {
            writeAndAwaitAck(SonyMessageCodec.encode(request, capabilities, dataSequence))
        }
    }

    suspend fun setNoiseCancelling(state: NoiseCancelling): Boolean =
        send(SonyRequest.SetNoiseControl(state, adaptive, ambientLevel))

    /** Null when the headset does not offer noise control, or never answered. */
    suspend fun readNoiseCancelling(): SonyResponse.NoiseControl? =
        request(SonyRequest.NoiseControl, RESPONSE_TIMEOUT_MS) { it as? SonyResponse.NoiseControl }
            ?.also { adaptive = it.adaptive ?: adaptive }

    suspend fun readBattery(): BatteryReading? =
        request(SonyRequest.Battery, RESPONSE_TIMEOUT_MS) { (it as? SonyResponse.Battery)?.reading }

    suspend fun readEqualizerPreset(): SonyResponse.Equalizer? =
        request(SonyRequest.Equalizer, RESPONSE_TIMEOUT_MS) { it as? SonyResponse.Equalizer }

    // endregion

    // region handshake

    private suspend fun handshake() {
        val protocolInfo =
            handshake(SonyHandshakeRequest.ProtocolInfo) { it as? SonyResponse.ProtocolInfo }
                ?: throw IOException("the headset never reported its protocol version")
        Timber.i("Protocol ${protocolInfo.version}, tables ${protocolInfo.tables}")
        if (protocolInfo.tables.isNotEmpty() && ProtocolTable.Table1 !in protocolInfo.tables) {
            // Every command this client sends travels on table 1, so a device with it switched off
            // would answer nothing and look like a headset that had gone quiet.
            throw IOException(
                "the headset reports protocol ${protocolInfo.version} with table 1 disabled; " +
                    "the commands this client sends all live there",
            )
        }

        val functions =
            handshake(SonyHandshakeRequest.SupportedFunctions) {
                it as? SonyResponse.SupportedFunctions
            } ?: throw IOException("the headset never listed its supported functions")
        val capabilities = SonyCapabilities(protocolInfo.version, functions.functionIds)
        _capabilities.value = capabilities
        Timber.i(
            "Headset offers battery=${capabilities.batteryShape} " +
                "noiseCancelling=${capabilities.noiseCancelling}",
        )

        for (question in DEVICE_INFO_QUESTIONS) {
            handshake(SonyHandshakeRequest.DeviceInfo(question)) { it as? SonyResponse.DeviceInfo }
        }

        // Both controls are read once so a screen has something to show before anything changes.
        readNoiseCancelling()
        if (capabilities.supportsEqualizerPreset) readEqualizerPreset()
        readBattery()
        handshaken.complete(Unit)
    }

    private suspend fun poll() {
        while (!closed) {
            delay(POLL_INTERVAL_MS)
            // Not every headset pushes battery on its own.
            readBattery()
        }
    }

    // endregion

    // region incoming

    private fun onNotification(value: ByteArray) {
        for (frame in assembler.feed(value)) {
            if (!incoming.trySend(frame).isSuccess) {
                Timber.w("Dropped an incoming frame: the queue is full")
            }
        }
    }

    private suspend fun processIncoming() {
        try {
            for (frame in incoming) {
            val response = SonyMessageCodec.decode(frame, _capabilities.value?.version)
            // Every data frame is acknowledged, the ones this client cannot read included: an
            // unacknowledged headset resends, which turns one unknown command into a loop.
            if (frame.type != MdrDataType.Ack) acknowledge(frame.sequence)
            when (response) {
                is SonyResponse.Acknowledged -> {
                    if (!acknowledgements.trySend(response.sequence).isSuccess) {
                        Timber.w("Dropped an acknowledgement: the queue is full")
                    }
                }
                is SonyResponse.Unrecognised -> Timber.d("Ignoring command ${response.command}")
                else -> {
                    rememberAmbientLevel(response)
                    val command = frame.payload.firstOrNull() ?: 0
                    waiters
                        .firstOrNull { it.matches(response) && it.expectedReplyCommand == command }
                        ?.let { waiter ->
                            waiters.remove(waiter)
                            waiter.channel.trySend(response)
                        }
                    _responses.tryEmit(response)
                }
                }
            }
        } finally {
            finished.complete(Unit)
        }
    }

    private fun rememberAmbientLevel(response: SonyResponse) {
        val ambient = (response as? SonyResponse.NoiseControl)?.state as? NoiseCancelling.Ambient
        if (ambient != null) ambientLevel = ambient.level
    }

    private suspend fun acknowledge(sequence: Int) {
        // An acknowledgement carries the other value of the one bit sequence it is answering.
        val frame = MdrFrame(MdrDataType.Ack, inverse(sequence), ByteArray(0))
        writeFrame(frame)
    }

    // endregion

    // region exchanges

    /** Sends [request] and waits for a response it accepts, in either order. */
    private suspend fun <T : Any> request(
        request: SonyRequest,
        timeoutMs: Long,
        matches: (SonyResponse) -> T?,
    ): T? {
        val capabilities = _capabilities.value ?: return null
        // Nothing is sent for a headset that never advertised the feature, and its absence is the
        // answer: a device that does not offer noise control has no noise control state to report.
        if (!request.supportedBy(capabilities)) return null
        return sendLock.withLock {
            exchange(SonyMessageCodec.encode(request, capabilities, dataSequence), timeoutMs, matches)
        }
    }

    private suspend fun <T : Any> handshake(
        request: SonyHandshakeRequest,
        matches: (SonyResponse) -> T?,
    ): T? = sendLock.withLock {
        exchange(SonyMessageCodec.encode(request, dataSequence), HANDSHAKE_TIMEOUT_MS, matches)
    }

    /**
     * Writes [frame] and waits for its acknowledgement and for a response [matches] accepts.
     *
     * The waiter is registered before the write because a headset is known to answer a request before
     * it acknowledges it, so the reply is often already on the wire by the time the ACK lands. The
     * waiter buffers one response, so the order the two arrive in does not matter.
     */
    private suspend fun <T : Any> exchange(
        frame: MdrFrame,
        timeoutMs: Long,
        matches: (SonyResponse) -> T?,
    ): T? {
        val waiter = ResponseWaiter(replyCommandFor(frame)) { matches(it) != null }
        waiters += waiter
        try {
            if (!writeAndAwaitAck(frame)) return null
            val response = withTimeoutOrNull(timeoutMs) { waiter.channel.receive() } ?: return null
            return matches(response)
        } finally {
            waiters -= waiter
        }
    }

    private suspend fun writeAndAwaitAck(frame: MdrFrame): Boolean {
        repeat(SEND_ATTEMPTS) { attempt ->
            drainAcknowledgements()
            if (!writeFrame(frame)) {
                // The stack refuses a write while it is busy with another operation. That is a moment
                // to wait out; giving up here would fail the whole handshake on a device whose stack
                // enforces it.
                Timber.d("Write refused, attempt ${attempt + 1} of $SEND_ATTEMPTS")
                delay(WRITE_RETRY_DELAY_MS)
                return@repeat
            }
            val expected = inverse(frame.sequence)
            val acknowledged =
                withTimeoutOrNull(ACK_TIMEOUT_MS) {
                    var received = false
                    while (!received) received = acknowledgements.receive() == expected
                    true
                } ?: false
            if (acknowledged) {
                // The next frame carries the value this acknowledgement carried.
                dataSequence = expected
                return true
            }
            Timber.w("No acknowledgement, attempt ${attempt + 1} of $SEND_ATTEMPTS")
        }
        return false
    }

    /** The reply to a GET carries the next command byte up; the protocol uses that throughout. */
    private fun replyCommandFor(frame: MdrFrame): Byte =
        ((frame.payload.firstOrNull()?.toInt() ?: 0) + 1).toByte()

    private fun drainAcknowledgements() {
        // Anything still queued belongs to a frame that already timed out.
        while (acknowledgements.tryReceive().isSuccess) {
            // Discarded on purpose.
        }
    }

    // endregion

    // region frames

    private suspend fun writeFrame(frame: MdrFrame): Boolean = writeLock.withLock {
        val connection = gatt ?: return@withLock false
        val characteristic = write ?: return@withLock false
        writeBytes(connection, characteristic, MdrFrameCodec.encode(frame))
    }

    @SuppressLint("MissingPermission") // the caller checks the permission before connecting
    @Suppress("DEPRECATION") // the value-carrying writeCharacteristic overload is API 33+
    private fun writeBytes(
        connection: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        // The payload a write carries at the default ATT MTU. No MTU is asked for, so nothing here
        // has to wait on the stack being free mid-handshake, and a frame longer than this is split:
        // the headset reassembles by scanning for the markers, which is what the reference clients do
        // too. Every frame this client sends fits in one write in practice.
        val chunks = bytes.asList().chunked(WRITE_CHUNK_BYTES)
        for (chunk in chunks) {
            characteristic.value = chunk.toByteArray()
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            if (!connection.writeCharacteristic(characteristic)) {
                Timber.w("writeCharacteristic refused")
                return false
            }
        }
        return true
    }

    private suspend fun subscribe() {
        val connection = gatt ?: throw IOException("no connection")
        val characteristic = notify ?: throw IOException("no notify characteristic")
        writeEnableNotifications(connection, characteristic)
        withTimeoutOrNull(GATT_TIMEOUT_MS) { subscribed.await() }
            ?: throw IOException("timed out subscribing to notifications")
    }

    @SuppressLint("MissingPermission") // the caller checks the permission before connecting
    @Suppress("DEPRECATION") // the value-carrying writeDescriptor overload is API 33+
    private fun writeEnableNotifications(
        connection: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        // Both calls are needed: the first tells the stack to pass notifications up, the second tells
        // the headset to send them. Miss the first and notifications are silently dropped locally.
        if (!connection.setCharacteristicNotification(characteristic, true)) {
            throw IOException("setCharacteristicNotification refused")
        }
        val cccd =
            characteristic.getDescriptor(SonyGatt.CCCD) ?: throw IOException("no CCCD on notify")
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!connection.writeDescriptor(cccd)) throw IOException("writeDescriptor refused")
    }

    // endregion

    private companion object {
        const val FRAME_QUEUE = 64
        const val RESPONSE_BUFFER = 64
        const val WRITE_CHUNK_BYTES = 20
        const val WRITE_RETRY_DELAY_MS = 50L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val GATT_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val RESPONSE_TIMEOUT_MS = 3_000L
        const val ACK_TIMEOUT_MS = 750L
        const val SEND_ATTEMPTS = 4
        const val POLL_INTERVAL_MS = 60_000L

        val DEVICE_INFO_QUESTIONS = listOf(DeviceInfoQuestion.ModelName, DeviceInfoQuestion.FirmwareVersion)

        fun inverse(sequence: Int): Int = 1 - sequence.coerceIn(0, 1)
    }
}
