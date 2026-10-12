package fi.nikosavola.xmheadphones.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Every packet here was captured from a WH-1000XM3 on firmware 4.5.2 and is committed to
 * mos9527/SonyHeadphonesClient under `tests/WH-1000XM3-4.5.2/`, so the expected values are the
 * device's real answers rather than what this client happens to compute.
 */
class SonyMessageCodecTest {

    private val v1 =
        SonyCapabilities(
            version = ProtocolVersion.V1,
            functionIds = XM3_FUNCTIONS,
        )

    @Test
    fun `the protocol info reply settles the version as V1`() {
        val response = decode("3e0c000000000401004010613c")

        assertEquals(
            SonyResponse.ProtocolInfo(ProtocolVersion.V1, setOf(ProtocolTable.Table1)),
            response,
        )
    }

    @Test
    fun `a four byte version with table flags settles as V2 with both tables`() {
        // The shape of libmdr's ConnectRetProtocolInfo: command, type, a four byte version, then one
        // flag per command table, where 0x00 means enabled.
        val frame =
            MdrFrameCodec.encode(
                MdrFrame(
                    MdrDataType.DataMdr,
                    0,
                    byteArrayOf(0x01, 0x00, 0x03, 0x00, 0x00, 0x15, 0x00, 0x00),
                ),
            )

        assertEquals(
            SonyResponse.ProtocolInfo(ProtocolVersion.V2, setOf(ProtocolTable.Table1, ProtocolTable.Table2)),
            decode(frame),
        )
    }

    @Test
    fun `a V2 device that disabled table one is still reported faithfully`() {
        val frame =
            MdrFrameCodec.encode(
                MdrFrame(
                    MdrDataType.DataMdr,
                    0,
                    byteArrayOf(0x01, 0x00, 0x03, 0x00, 0x00, 0x15, 0x01, 0x00),
                ),
            )

        assertEquals(
            SonyResponse.ProtocolInfo(ProtocolVersion.V2, setOf(ProtocolTable.Table2)),
            decode(frame),
        )
    }

    @Test
    fun `the device info replies carry the model name and firmware`() {
        assertEquals(
            SonyResponse.DeviceInfo(DeviceInfoQuestion.ModelName, "WH-1000XM3"),
            decode("3e0c000000000d05010a57482d31303030584d338e3c"),
        )
        assertEquals(
            SonyResponse.DeviceInfo(DeviceInfoQuestion.FirmwareVersion, "4.5.2"),
            decode("3e0c0100000008050205342e352e32183c"),
        )
    }

    @Test
    fun `series and colour come back without a string`() {
        assertEquals(
            SonyResponse.DeviceInfo(DeviceInfoQuestion.SeriesAndColor, null),
            decode("3e0c000000000405033001493c"),
        )
    }

    @Test
    fun `the advertised functions are read off the support function reply`() {
        val response = decode("3e0c010000001607001311c112131430716281424151a1e1e2d1d2f439d43c")

        val functions = (response as SonyResponse.SupportedFunctions).functionIds
        assertEquals(19, functions.size)
        assertEquals(XM3_FUNCTIONS, functions)
    }

    @Test
    fun `the advertised functions say the XM3 does noise cancelling, battery and the equalizer`() {
        assertEquals(BatteryShape.Single, v1.batteryShape)
        assertEquals(NcAsmDialect.V1, v1.noiseCancelling)
        assertEquals(true, v1.supportsEqualizerPreset)
    }

    @Test
    fun `an acknowledgement is matched by sequence number`() {
        assertEquals(SonyResponse.Acknowledged(1), decode("3e010100000000023c"))
    }

    @Test
    fun `a reported noise cancelling parameter reads as cancelling`() {
        val response = decode("3e0c00000000086702010202010000833c")

        assertEquals(
            SonyResponse.NoiseControl(NoiseCancelling.Cancelling, settled = true, adaptive = null),
            response,
        )
    }

    @Test
    fun `the same parameter with the effect switched off reads as off`() {
        val response = decode("3e0c01000000086902000202010000853c")

        assertEquals(
            SonyResponse.NoiseControl(NoiseCancelling.Off, settled = true, adaptive = null),
            response,
        )
    }

    @Test
    fun `a notification with the effect on reads as cancelling`() {
        val response = decode("3e0c01000000086902010202010000863c")

        assertEquals(
            SonyResponse.NoiseControl(NoiseCancelling.Cancelling, settled = true, adaptive = null),
            response,
        )
    }

    @Test
    fun `an unmodelled command is reported rather than dropped`() {
        // The equalizer EBB reply, which carries a curve this client does not show.
        assertEquals(
            SonyResponse.Unrecognised(0x57),
            decode("3e0c010000000a570100060a0a0a0a0a0ab13c"),
        )
    }

    @Test
    fun `a reply shorter than its own shape requires is refused`() {
        // A battery reply with the level byte missing.
        val truncated =
            MdrFrameCodec.encode(MdrFrame(MdrDataType.DataMdr, 0, byteArrayOf(0x11, 0x00, 0x50)))

        assertEquals(SonyResponse.Unrecognised(0x11), decode(truncated))
    }

    @Test
    fun `a left and right battery reply reports both buds`() {
        val frame =
            MdrFrameCodec.encode(
                MdrFrame(MdrDataType.DataMdr, 0, byteArrayOf(0x11, 0x01, 0x50, 0x00, 0x64, 0x01)),
            )

        val response = decode(frame)

        assertEquals(
            SonyResponse.Battery(
                BatteryReading.LeftRight(
                    BatteryStatus(80, charging = false),
                    BatteryStatus(100, charging = true),
                ),
            ),
            response,
        )
    }

    @Test
    fun `an empty payload is reported rather than read out of bounds`() {
        val frame = MdrFrame(MdrDataType.DataMdr, 0, ByteArray(0))

        assertEquals(SonyResponse.Unrecognised(-1), decode(frame))
    }

    @Test
    fun `the battery request asks the shape the device advertised`() {
        val single = SonyMessageCodec.encode(SonyRequest.Battery, v1, sequence = 3)
        assertContentEquals(byteArrayOf(0x10, 0x00), single.payload)

        val earbuds =
            SonyCapabilities(
                ProtocolVersion.V2,
                functionIds = setOf(0x21),
            )
        val leftRight = SonyMessageCodec.encode(SonyRequest.Battery, earbuds, sequence = 3)
        assertContentEquals(byteArrayOf(0x22, 0x01), leftRight.payload)
    }

    @Test
    fun `a request for something the device never advertised is refused, not sent`() {
        val batteryLess = SonyCapabilities(ProtocolVersion.V1, functionIds = emptySet())

        assertNull(runCatching { SonyMessageCodec.encode(SonyRequest.Battery, batteryLess, 0) }.getOrNull())
        assertNull(
            runCatching {
                    SonyMessageCodec.encode(
                        SonyRequest.SetNoiseControl(NoiseCancelling.Cancelling),
                        batteryLess,
                        0,
                    )
                }
                .getOrNull(),
        )
    }

    private fun decode(bytes: ByteArray): SonyResponse =
        when (val result = MdrFrameCodec.decode(bytes)) {
            is MdrDecodeResult.Decoded -> SonyMessageCodec.decode(result.frame)
            else -> error("the packet did not decode: $result")
        }

    private fun decode(hex: String): SonyResponse = decode(hex(hex))

    private fun decode(frame: MdrFrame): SonyResponse = SonyMessageCodec.decode(frame)

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        /** The nineteen functions the WH-1000XM3 advertised, in the order it sent them. */
        val XM3_FUNCTIONS: Set<Int> =
            setOf(
                0x11, 0xC1, 0x12, 0x13, 0x14, 0x30, 0x71, 0x62, 0x81, 0x42, 0x41, 0x51, 0xA1, 0xE1,
                0xE2, 0xD1, 0xD2, 0xF4, 0x39,
            )
    }
}
