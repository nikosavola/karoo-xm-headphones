package fi.nikosavola.xmheadphones.client

import fi.nikosavola.xmheadphones.protocol.MdrDataType
import fi.nikosavola.xmheadphones.protocol.MdrDecodeResult
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameCodec
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolVersion
import fi.nikosavola.xmheadphones.protocol.SonyCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral

/**
 * A headset that speaks the wire format by hand.
 *
 * The replies are built from literal bytes rather than from this project's codec on purpose: a fake
 * that shares the code under test would agree with it about a mistake. What it does share is
 * [MdrFrameCodec], because the framing itself is checked against a real device capture elsewhere and
 * rewriting it here would only create a second thing to keep right.
 */
internal class FakeHeadset(
    val peripheral: FakeBlePeripheral,
    private val version: ProtocolVersion = ProtocolVersion.V1,
    functions: List<Int> = V1_FUNCTIONS,
    /** A V2 headset can report either command table switched off, which makes it unusable. */
    private val table1Enabled: Boolean = true,
) {
    private val functionIds = functions.toList()

    @Volatile var batteryPercent: Int = 80
    @Volatile var charging: Boolean = false
    @Volatile var equalizerPreset: Int = 0x02

    /** What the headset thinks it was last told, decoded from the write the client sent. */
    @Volatile var noiseCancelling: NoiseCancelling? = NoiseCancelling.Cancelling

    /** Every frame the client wrote, in order, unparsed. */
    val received = CopyOnWriteArrayList<ByteArray>()


    private var replySequence = 0

    fun install() {
        peripheral.onWrite(SonyGatt.WRITE) { request -> handle(request) }
    }

    /** Everything the client wrote that was an acknowledgement, by the sequence it acknowledged. */
    fun acknowledgedSequences(): List<Int> =
        received.mapNotNull { bytes ->
            val frame = decode(bytes) ?: return@mapNotNull null
            if (frame.type == MdrDataType.Ack) 1 - frame.sequence else null
        }

    /** The commands the client sent, without the acknowledgements. */
    fun commands(): List<Int> =
        received.mapNotNull { bytes ->
            val frame = decode(bytes) ?: return@mapNotNull null
            if (frame.type == MdrDataType.Ack || frame.payload.isEmpty()) {
                null
            } else {
                frame.payload[0].toInt() and 0xFF
            }
        }

    /** The last noise-control write, as the headset read it. */
    fun lastNoiseControlWrite(): ByteArray? = lastPayload(NC_SET)

    fun pushNoiseCancelling(state: NoiseCancelling) {
        noiseCancelling = state
        send(command(NC_NTFY, noiseControlFields()))
    }

    fun disconnect() = peripheral.disconnect()

    private fun handle(bytes: ByteArray) {
        received += bytes
        val frame = decode(bytes) ?: return
        // An acknowledgement is not itself acknowledged, and needs no reply.
        if (frame.type == MdrDataType.Ack) return
        val payload = frame.payload
        sendAck(1 - frame.sequence)
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            CONNECT_GET_PROTOCOL_INFO -> send(command(0x01, protocolInfo()))
            CONNECT_GET_DEVICE_INFO -> send(command(0x05, deviceInfo(payload[1])))
            CONNECT_GET_SUPPORT_FUNCTION -> send(command(0x07, supportFunctions()))
            BATTERY_GET_V1,
            BATTERY_GET_V2 -> send(command(batteryReplyCommand(), battery()))
            NC_GET -> send(command(NC_RET, noiseControlFields()))
            NC_SET -> {
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
            ProtocolVersion.V2 -> byteArrayOf(0x17) + v2Fields(noiseCancelling ?: NoiseCancelling.Off)
        }

    /** The six V1 fields after the inquired type: effect, ncType, ncValue, asmType, asmId, asmValue. */
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

    private fun lastPayload(command: Int): ByteArray? =
        received
            .mapNotNull { decode(it) }
            .lastOrNull { it.type != MdrDataType.Ack && (it.payload.firstOrNull()?.toInt() ?: 0) == command }
            ?.payload

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
        val frame = MdrFrame(MdrDataType.DataMdr, replySequence, payload)
        replySequence = 1 - replySequence
        peripheral.notify(SonyGatt.NOTIFY, MdrFrameCodec.encode(frame))
    }

    private fun sendAck(sequence: Int) {
        peripheral.notify(
            SonyGatt.NOTIFY,
            MdrFrameCodec.encode(MdrFrame(MdrDataType.Ack, sequence, ByteArray(0))),
        )
    }

    private fun decode(bytes: ByteArray): MdrFrame? =
        (MdrFrameCodec.decode(bytes) as? MdrDecodeResult.Decoded)?.frame

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

        const val MODEL_NAME = "WH-1000XM3"
        const val FIRMWARE = "4.5.2"

        /** The functions a WH-1000XM3 advertised, which is what makes the codec pick the V1 layout. */
        val V1_FUNCTIONS =
            listOf(
                0x11, 0xC1, 0x12, 0x13, 0x14, 0x30, 0x71, 0x62, 0x81, 0x42, 0x41, 0x51, 0xA1, 0xE1,
                0xE2, 0xD1, 0xD2, 0xF4, 0x39,
            )

        /** What a V2 headset of the XM5 family advertises, enough for battery and noise control. */
        val V2_FUNCTIONS = listOf(0x20, 0x50, 0x6B)

        fun capabilities(version: ProtocolVersion, functions: List<Int> = V1_FUNCTIONS) =
            SonyCapabilities(version, functions.toSet())
    }
}
