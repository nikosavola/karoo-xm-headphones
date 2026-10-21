package fi.nikosavola.xmheadphones.client

import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.BatteryReading
import fi.nikosavola.xmheadphones.protocol.DeviceInfoQuestion
import fi.nikosavola.xmheadphones.protocol.MdrDataType
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameAssembler
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * One connected headset: the protocol over an open [HeadsetTransport].
 *
 * The protocol is stop and wait, one frame on the wire at a time, each acknowledged before the next
 * is sent, so this owns one loop that reads what arrives and one lock that serialises what goes out.
 *
 * [run] returns only when the link is gone, and nothing here throws because a headset misbehaved. A
 * frame this client cannot read is logged and acknowledged anyway, because a headset left
 * unacknowledged keeps resending it.
 */
internal class SonySession(private val transport: HeadsetTransport) : AutoCloseable {
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
     * pressure. These are state reports, and the next poll refreshes whatever is lost.
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

    @Volatile private var closed = false
    /** What the last noise-control report carried, kept because [setNoiseCancelling] echoes it back. */
    @Volatile private var adaptive: NoiseAdaptiveFields = NoiseAdaptiveFields.Default

    /**
     * The ambient level the headset last reported. Keeping it means switching noise cancelling off
     * does not make the headset forget the level while it is off.
     */
    @Volatile private var ambientLevel: AmbientLevel = AmbientLevel.Silent

    /**
     * The sequence the next frame we send carries; the protocol has a two value sequence space.
     *
     * Every read and write of this is under [sendLock], which is why it is not volatile.
     */
    private var dataSequence = 0

    /**
     * Held for the length of one write, and nothing else. The pipe carries commands and
     * acknowledgements together, so two writes racing would interleave their bytes on the wire and
     * neither would decode. Deliberately not [sendLock]: the read loop acknowledges from here, and
     * holding the command lock across that would deadlock against a reply that arrives before its
     * own acknowledgement.
     */
    private val writeLock = Mutex()

    /**
     * A response the session is waiting for.
     *
     * [expectedReplyCommand] is the command byte the headset answers with. For every GET in this
     * protocol that is the request's own command plus one. Matching on the response type alone would
     * let a report the headset raised itself satisfy a pending request, since both decode to the
     * same type.
     */
    private class ResponseWaiter(
        val expectedReplyCommand: Byte,
        val matches: (SonyResponse) -> Boolean,
    ) {
        val channel = Channel<SonyResponse>(capacity = 1)
    }

    /**
     * Shakes hands, then serves requests until the link drops.
     *
     * The pipe is already open when this is called, so a headset that never answered has thrown by
     * now, in the caller's own connect step.
     */
    suspend fun run() {
        scope.launch { readFrames() }
        scope.launch { processIncoming() }
        handshake()
        // The poll loop keeps the battery fresh while this waits for the read loop to finish, which
        // is what a link going away looks like from here.
        scope.launch { poll() }
        finished.await()
        Timber.i("The link is gone")
    }

    override fun close() {
        closed = true
        scope.cancel()
        // Before the queue: a read already blocked in the pipe only comes out of it when the socket
        // under it goes away, and until it does the read loop cannot finish.
        transport.close()
        incoming.close()
    }

    // region commands

    /**
     * Sends [request] and waits for the headset to acknowledge it.
     *
     * False means it never did, which is the only honest answer available here. The link is slow, and
     * a frame that was written is not a frame that arrived.
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
            // Every command this client sends travels on table 1. A device with that table switched
            // off answers nothing, which looks like a headset that has gone quiet.
            throw IOException(
                "the headset reports protocol ${protocolInfo.version} with table 1 disabled; " +
                    "the commands this client sends all live there",
            )
        }

        val functions =
            handshake(SonyHandshakeRequest.SupportedFunctions) {
                it as? SonyResponse.SupportedFunctions
            } ?: throw IOException("the headset never listed its supported functions")
        val capabilities =
            SonyCapabilities(protocolInfo.version, functions.functionIds, protocolInfo.tables)
        _capabilities.value = capabilities
        Timber.i(
            "Headset offers battery=${capabilities.batteryShape} " +
                "noiseCancelling=${capabilities.noiseCancelling}",
        )

        for (question in DEVICE_INFO_QUESTIONS) {
            handshake(SonyHandshakeRequest.DeviceInfo(question)) { it as? SonyResponse.DeviceInfo }
        }

        // Both controls are read once, so a screen has something to show before anything changes.
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

    /**
     * Reads the pipe and splits it into frames until it ends.
     *
     * The headset's own read of a frame is a message boundary, but the bytes reaching this are not:
     * a frame can arrive across several reads and one read can carry several frames. The assembler
     * is what puts them back together, and it does not need to know where either edge fell.
     */
    private suspend fun readFrames() {
        try {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (true) {
                val read = withContext(Dispatchers.IO) { transport.input.read(buffer) }
                if (read < 0) break
                if (read == 0) continue
                for (frame in assembler.feed(buffer.copyOf(read))) {
                    if (!incoming.trySend(frame).isSuccess) {
                        Timber.w("Dropped an incoming frame: the queue is full")
                    }
                }
            }
        } catch (failure: IOException) {
            Timber.d("The link stopped reading: ${failure.message}")
        } finally {
            finished.complete(Unit)
            incoming.close()
        }
    }

    private suspend fun processIncoming() {
        try {
            for (frame in incoming) {
                val response = SonyMessageCodec.decode(frame, _capabilities.value?.version)
                // Every data frame is acknowledged, including the ones this client cannot read. A
                // headset left unacknowledged resends, which turns one unknown command into a loop.
                if (frame.type != MdrDataType.Ack) acknowledge(frame.sequence)
                when (response) {
                    is SonyResponse.Acknowledged -> {
                        if (!acknowledgements.trySend(response.sequence).isSuccess) {
                            Timber.w("Dropped an acknowledgement: the queue is full")
                        }
                    }
                    is SonyResponse.Unrecognised -> Timber.d("Ignoring command ${response.command}")
                    else -> {
                        if (response is SonyResponse.NoiseControl) rememberNoiseControl(response)
                        val command = frame.payload.firstOrNull() ?: 0
                        waiters
                            .firstOrNull {
                                it.matches(response) && it.expectedReplyCommand == command
                            }
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

    /**
     * Keeps the parts of a noise-control report this client only echoes back.
     *
     * Every report counts, not just a polled one. The rider can move the mode from the headset's own
     * button or from another app, and sending back the adaptive fields from before that would undo
     * what they set.
     */
    private fun rememberNoiseControl(noiseControl: SonyResponse.NoiseControl) {
        val ambient = noiseControl.state as? NoiseCancelling.Ambient
        if (ambient != null) ambientLevel = ambient.level
        noiseControl.adaptive?.let { adaptive = it }
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
        // Nothing is sent to a headset that never advertised the feature, and its silence is the
        // answer. A device without noise control has no noise control state to report.
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
     * Writes [frame] and waits for its acknowledgement and for a response [matches] accepts. The waiter
     * is registered first, because a headset can answer before it acknowledges, so the reply is often
     * already on the wire when the ACK lands. It buffers one response, so the order does not matter.
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
                // A pipe that refused a write is usually one that has just gone away, and the link is
                // about to end anyway. Waiting once covers the other case: a headset that stopped
                // reading its side of the socket for a moment, where the write goes through next time.
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

    /** The reply to a GET carries the next command byte up, which the protocol uses throughout. */
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
        try {
            withContext(Dispatchers.IO) {
                transport.output.write(MdrFrameCodec.encode(frame))
                // The socket buffers, and an acknowledgement held in a buffer is one the headset is
                // still waiting for.
                transport.output.flush()
            }
            true
        } catch (failure: IOException) {
            Timber.w("The frame did not go out: ${failure.message}")
            false
        }
    }

    // endregion

    private companion object {
        const val FRAME_QUEUE = 64
        const val RESPONSE_BUFFER = 64
        const val READ_BUFFER_BYTES = 512
        const val WRITE_RETRY_DELAY_MS = 50L
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val RESPONSE_TIMEOUT_MS = 3_000L
        const val ACK_TIMEOUT_MS = 750L
        const val SEND_ATTEMPTS = 4
        const val POLL_INTERVAL_MS = 60_000L

        val DEVICE_INFO_QUESTIONS = listOf(DeviceInfoQuestion.ModelName, DeviceInfoQuestion.FirmwareVersion)

        fun inverse(sequence: Int): Int = 1 - sequence.coerceIn(0, 1)
    }
}
