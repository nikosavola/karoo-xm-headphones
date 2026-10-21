package fi.nikosavola.xmheadphones.client

import android.bluetooth.BluetoothDevice
import fi.nikosavola.xmheadphones.protocol.MdrDataType
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameAssembler
import fi.nikosavola.xmheadphones.protocol.MdrFrameCodec
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolVersion
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A headset that speaks the wire format by hand.
 *
 * Replies are literal bytes on purpose. A fake that shares the code under test would agree with its
 * mistakes. The one thing it does share is [MdrFrameCodec], since framing is checked against a real
 * capture elsewhere and a second implementation would be one more thing to keep right.
 *
 * It is a [HeadsetTransportFactory], so the client opens it the way it opens a socket.
 */
internal class FakeHeadset(
    private val version: ProtocolVersion = ProtocolVersion.V1,
    functions: List<Int> = V1_FUNCTIONS,
    /** A V2 headset can report either command table switched off, which makes it unusable. */
    private val table1Enabled: Boolean = true,
) : HeadsetTransportFactory {
    private val functionIds = functions.toList()

    @Volatile var batteryPercent: Int = 80
    @Volatile var charging: Boolean = false
    @Volatile var equalizerPreset: Int = 0x02

    /** What the headset was last told, decoded from the client's write. */
    @Volatile var noiseCancelling: NoiseCancelling? = NoiseCancelling.Cancelling

    /** When set, the headset pushes a notification of its own before it answers a set. */
    @Volatile var interruptSets: Boolean = false

    /** Auto-ambient and sensitivity, which only a noise-adaptive V2 headset reports. */
    @Volatile var adaptive: ByteArray = byteArrayOf(0x00, 0x00)

    /** When false the channel refuses to open, the way a headset that is off or out of range does. */
    @Volatile var answers: Boolean = true

    /**
     * What [carriesAudio] says, which is the sign the client uses to skip an attempt altogether.
     *
     * A fake answers true by default, since it stands for a headset that is there and answering.
     */
    @Volatile var carriesAudio: Boolean = true

    /**
     * When false the handshake completes but the questions about the headset itself go unanswered,
     * which is how a model with no device-info command behaves.
     */
    @Volatile var answersDeviceInfo: Boolean = true

    /** How many times the client has tried to open the channel, answered or not. */
    val openAttempts = AtomicInteger()

    /**
     * The addresses [looksReachable] says yes to.
     *
     * The real transport recognises its peer by the services the device advertises. This one is told
     * instead, so a test can put two paired devices in a known order without a radio.
     */
    @Volatile var reachableAddresses: Set<String> = emptySet()

    /** Every frame the client wrote, in order. */
    val receivedFrames = CopyOnWriteArrayList<MdrFrame>()

    /** Every reply this headset pushed, by command byte, so a test can say what it raised itself. */
    private val pushed = CopyOnWriteArrayList<Int>()

    /** The addresses the client asked for, so a test can say which device it went to. */
    val openedAddresses = CopyOnWriteArrayList<String>()

    fun pushedCountOf(command: Int): Int = pushed.count { it == command }

    @Volatile private var link: Link? = null

    override fun carriesAudio(device: BluetoothDevice): Boolean = carriesAudio

    override suspend fun open(device: BluetoothDevice): HeadsetTransport {
        openAttempts.incrementAndGet()
        // Recorded before the refusal, so a test can say which device the client went to even when
        // none of them answers.
        openedAddresses += device.address
        if (!answers) throw IOException("no route to host")
        // A headset starts its own sequence over on a new connection, as a fresh socket would.
        replySequence = 0
        return Link().also { link = it }
    }

    /** The commands the client sent, without the acknowledgements. */
    fun commands(): List<Int> =
        receivedFrames.mapNotNull { frame ->
            if (frame.type == MdrDataType.Ack || frame.payload.isEmpty()) {
                null
            } else {
                frame.payload[0].toInt() and 0xFF
            }
        }

    /** Everything the client wrote that was an acknowledgement, by the sequence it acknowledged. */
    fun acknowledgedSequences(): List<Int> =
        receivedFrames.filter { it.type == MdrDataType.Ack }.map { 1 - it.sequence }

    /** Every battery question the client asked, as the headset read it, questioned byte included. */
    fun batteryRequests(): List<List<Int>> =
        receivedFrames
            .filter { it.type != MdrDataType.Ack && commandOf(it) in BATTERY_GET_COMMANDS }
            .map { frame -> frame.payload.map { it.toInt() and 0xFF } }

    private fun commandOf(frame: MdrFrame): Int =
        (frame.payload.firstOrNull()?.toInt() ?: 0) and 0xFF

    /** The last noise-control write, as the headset read it. */
    fun lastNoiseControlWrite(): ByteArray? =
        receivedFrames
            .lastOrNull { it.type != MdrDataType.Ack && it.payload.firstOrNull() == NC_SET.toByte() }
            ?.payload

    fun pushNoiseCancelling(state: NoiseCancelling) {
        noiseCancelling = state
        send(command(NC_NTFY, noiseControlFields()))
    }

    override fun looksReachable(device: BluetoothDevice): Boolean =
        device.address in reachableAddresses

    /** The headset going away, which is what a read from the channel then running out looks like. */
    fun disconnect() = link?.close()

    private var replySequence = 0

    /**
     * One open connection.
     *
     * Reads block until the headset says something or ends, which is what the session expects of a
     * pipe, and writes are split back into frames rather than assumed to arrive whole.
     */
    private inner class Link : HeadsetTransport {
        private val inbound = LinkedBlockingQueue<ByteArray>()
        private val assembler = MdrFrameAssembler()
        private var pending = ByteArray(0)
        private var offset = 0

        @Volatile private var closed = false

        override val input: InputStream =
            object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
                }

                override fun read(destination: ByteArray, start: Int, length: Int): Int {
                    while (true) {
                        if (offset < pending.size) {
                            val count = minOf(length, pending.size - offset)
                            pending.copyInto(destination, start, offset, offset + count)
                            offset += count
                            return count
                        }
                        if (closed) return -1
                        val next = inbound.poll(READ_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                        pending = next
                        offset = 0
                    }
                }
            }

        override val output: OutputStream =
            object : OutputStream() {
                override fun write(one: Int) = write(byteArrayOf(one.toByte()), 0, 1)

                override fun write(source: ByteArray, start: Int, length: Int) {
                    for (frame in assembler.feed(source.copyOfRange(start, start + length))) {
                        receivedFrames += frame
                        handle(frame)
                    }
                }

                override fun flush() = Unit
            }

        override fun close() {
            closed = true
        }

        /** Pushes a data frame, and returns every frame that is now complete. */
        fun deliver(payload: ByteArray) {
            val frame = MdrFrame(MdrDataType.DataMdr, replySequence, payload)
            replySequence = 1 - replySequence
            push(frame)
        }

        fun push(frame: MdrFrame) {
            payloadOf(frame).let { pushed += it }
            if (!closed) inbound.offer(MdrFrameCodec.encode(frame))
        }

        private fun payloadOf(frame: MdrFrame): Int = frame.payload.firstOrNull()?.toInt() ?: 0
    }

    private fun handle(frame: MdrFrame) {
        // An acknowledgement is not itself acknowledged, and needs no reply.
        if (frame.type == MdrDataType.Ack) return
        val payload = frame.payload
        sendAck(1 - frame.sequence)
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            CONNECT_GET_PROTOCOL_INFO -> send(command(0x01, protocolInfo()))
            CONNECT_GET_DEVICE_INFO -> if (answersDeviceInfo) send(command(0x05, deviceInfo(payload[1])))
            CONNECT_GET_SUPPORT_FUNCTION -> send(command(0x07, supportFunctions()))
            BATTERY_GET_V1,
            BATTERY_GET_V2 -> send(command(batteryReplyCommand(), battery()))
            NC_GET -> send(command(NC_RET, noiseControlFields()))
            NC_SET -> {
                // A headset that reports a change of its own before it answers, so a set is in
                // flight while this lands.
                if (interruptSets) {
                    noiseCancelling = NoiseCancelling.Off
                    send(command(NC_NTFY, noiseControlFields()))
                }
                noiseCancelling = decodeNoiseControl(payload)
                send(command(NC_NTFY, noiseControlFields()))
            }
            EQ_GET -> send(command(0x57, equalizerReply()))
            else -> Unit // A command this fake does not model is acknowledged and otherwise ignored.
        }
    }

    private fun protocolInfo(): ByteArray =
        when (version) {
            // Two byte version, then nothing else.
            ProtocolVersion.V1 -> hex("00" + "4010")
            // Four byte version, then one enable flag per command table, where 0x00 is enabled.
            ProtocolVersion.V2 ->
                hex("00" + "03000015" + (if (table1Enabled) "00" else "01") + "00")
        }

    private fun deviceInfo(question: Byte) =
        when (question.toInt() and 0xFF) {
            0x01 -> prefixed(MODEL_NAME)
            0x02 -> prefixed(FIRMWARE)
            // Series and colour answer with two bytes rather than a string.
            else -> byteArrayOf(question, 0x30, 0x01)
        }

    private fun supportFunctions(): ByteArray =
        when (version) {
            // A flat list of ids.
            ProtocolVersion.V1 ->
                byteArrayOf(0x00, functionIds.size.toByte()) +
                    functionIds.map(Int::toByte).toByteArray()
            // The same list, each id followed by its order in the table.
            ProtocolVersion.V2 ->
                byteArrayOf(0x00, functionIds.size.toByte()) +
                    functionIds.flatMap { listOf(it.toByte(), 0x00) }.toByteArray()
        }

    /** The slot the preset lives in is 0x01 on V1 and 0x00 on V2, as a real headset reports it. */
    private fun equalizerReply(): ByteArray =
        byteArrayOf(
            if (version == ProtocolVersion.V1) 0x01 else 0x00,
            equalizerPreset.toByte(),
            0x00,
        )

    private fun battery(): ByteArray =
        byteArrayOf(0x00, batteryPercent.toByte(), if (charging) 0x01 else 0x00)

    private fun batteryReplyCommand(): Int =
        if (version == ProtocolVersion.V1) BATTERY_RET_V1 else BATTERY_RET_V2

    private fun noiseControlFields(): ByteArray =
        when (version) {
            ProtocolVersion.V1 -> byteArrayOf(0x02) + v1Fields(noiseCancelling ?: NoiseCancelling.Off)
            ProtocolVersion.V2 ->
                byteArrayOf(ncAsmInquiredType().toByte()) +
                    v2Fields(noiseCancelling ?: NoiseCancelling.Off) +
                    if (ncAsmInquiredType() == NC_ASM_ADAPTIVE) adaptive else ByteArray(0)
        }

    /** What this headset advertises decides the parameter shape it answers with. */
    private fun ncAsmInquiredType(): Int =
        if (NC_ASM_ADAPTIVE_FUNCTION in functionIds) NC_ASM_ADAPTIVE else 0x17

    /** The six V1 fields after the inquired type, in the order effect, ncType, ncValue, asmType, asmId, asmValue. */
    private fun v1Fields(state: NoiseCancelling): ByteArray {
        val ambient = state as? NoiseCancelling.Ambient
        return byteArrayOf(
            if (state is NoiseCancelling.Off) 0x00 else 0x01,
            0x02, // Dual/single/off, which is what an XM3-class headset reports.
            if (state is NoiseCancelling.Cancelling) 0x02 else 0x00,
            0x01,
            if (ambient?.focusOnVoice == true) 0x01 else 0x00,
            (ambient?.level?.steps ?: 0).toByte(),
        )
    }

    /** The five V2 fields after the inquired type. */
    private fun v2Fields(state: NoiseCancelling): ByteArray {
        val ambient = state as? NoiseCancelling.Ambient
        return byteArrayOf(
            0x01, // Changed.
            if (state is NoiseCancelling.Off) 0x00 else 0x01,
            if (state is NoiseCancelling.Cancelling) 0x00 else 0x01,
            if (ambient?.focusOnVoice == true) 0x01 else 0x00,
            (ambient?.level?.steps ?: 0).toByte(),
        )
    }

    /** Reads a set payload the way the headset would, so the test can assert on the device's view. */
    private fun decodeNoiseControl(payload: ByteArray): NoiseCancelling? =
        when (payload[1].toInt() and 0xFF) {
            0x02 -> {
                val enabled = payload[2] == 0x01.toByte()
                val dual = payload[4] == 0x02.toByte()
                when {
                    !enabled -> NoiseCancelling.Off
                    dual -> NoiseCancelling.Cancelling
                    else ->
                        NoiseCancelling.Ambient(
                            fi.nikosavola.xmheadphones.protocol.AmbientLevel.clamped(
                                payload[7].toInt() and 0xFF,
                            ),
                            focusOnVoice = payload[6] == 0x01.toByte(),
                        )
                }
            }
            // 0x17 and its noise-adaptive 0x19 carry the same five fields; the extra two are appended.
            else -> {
                val enabled = payload[3] == 0x01.toByte()
                val noiseCancellingMode = payload[4] == 0x00.toByte()
                when {
                    !enabled -> NoiseCancelling.Off
                    noiseCancellingMode -> NoiseCancelling.Cancelling
                    else ->
                        NoiseCancelling.Ambient(
                            fi.nikosavola.xmheadphones.protocol.AmbientLevel.clamped(
                                payload[6].toInt() and 0xFF,
                            ),
                            focusOnVoice = payload[5] == 0x01.toByte(),
                        )
                }
            }
        }

    private fun command(command: Int, body: ByteArray): ByteArray =
        byteArrayOf(command.toByte()) + body

    /** The reply is labelled with the question it answers, as a real headset's is. */
    private fun prefixed(text: String): ByteArray {
        val bytes = text.toByteArray()
        return byteArrayOf(questionWire(text), bytes.size.toByte()) + bytes
    }

    private fun questionWire(text: String): Byte =
        if (text == MODEL_NAME) 0x01 else 0x02

    /** A data frame from the headset, carrying its own alternating sequence. */
    private fun send(payload: ByteArray) {
        link?.deliver(payload)
    }

    private fun sendAck(sequence: Int) {
        link?.push(MdrFrame(MdrDataType.Ack, sequence, ByteArray(0)))
    }

    private fun hexBytes(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun hex(text: String): ByteArray = hexBytes(text)

    internal companion object {
        const val CONNECT_GET_PROTOCOL_INFO = 0x00
        const val CONNECT_GET_DEVICE_INFO = 0x04
        const val CONNECT_GET_SUPPORT_FUNCTION = 0x06
        const val BATTERY_GET_V1 = 0x10
        const val BATTERY_RET_V1 = 0x11
        const val BATTERY_GET_V2 = 0x22
        const val BATTERY_RET_V2 = 0x23
        const val NC_GET = 0x66
        const val NC_RET = 0x67
        const val NC_SET = 0x68
        const val NC_NTFY = 0x69
        const val EQ_GET = 0x56

        /** Both versions' battery question, so a test can say which one went out. */
        val BATTERY_GET_COMMANDS = setOf(BATTERY_GET_V1, BATTERY_GET_V2)

        /** V2's own noise-adaptation function id, which advertises the wider parameter. */
        const val NC_ASM_ADAPTIVE_FUNCTION = 0x6D

        /** The inquired type that function's parameter comes back under. */
        const val NC_ASM_ADAPTIVE = 0x19

        const val MODEL_NAME = "WH-1000XM3"
        const val FIRMWARE = "4.5.2"

        /** How long the fake's read waits before looking again for the connection to be closed. */
        private const val READ_POLL_MS = 20L

        /** The functions a WH-1000XM3 advertised, which is what makes the codec pick the V1 layout. */
        val V1_FUNCTIONS =
            listOf(
                0x11, 0xC1, 0x12, 0x13, 0x14, 0x30, 0x71, 0x62, 0x81, 0x42, 0x41, 0x51, 0xA1, 0xE1,
                0xE2, 0xD1, 0xD2, 0xF4, 0x39,
            )

        /**
         * What a V2 headset of the XM5 family advertises, enough for battery and noise control.
         *
         * The battery and equalizer ids are the table's, which a V1 headset reports too; only the
         * noise-control one is this generation's own.
         */
        val V2_FUNCTIONS = listOf(0x11, 0x15, 0x50, 0x6B)

        /** The same, with the noise adaptation that adds auto-ambient and a sensitivity. */
        val V2_ADAPTIVE_FUNCTIONS = listOf(0x11, 0x15, 0x50, 0x6D)
    }
}
