package fi.nikosavola.xmheadphones.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three states a rider picks between have to survive a trip out to the device and back, in every
 * dialect. A write and the device's own report of the same state do not have to look identical: a
 * headset reports which parameters it has, while a write states the value it wants, so the tests
 * assert on the decoded state rather than on byte equality with a capture.
 */
class NcAsmParamCodecTest {

    @Test
    fun `cancelling round-trips through the V1 layout`() {
        val payload = setPayload(NoiseCancelling.Cancelling, NcAsmDialect.V1)

        assertContentEquals(hex("6802010102010000"), payload)
        assertEquals(NoiseCancelling.Cancelling, decodeState(payload))
    }

    @Test
    fun `off round-trips through the V1 layout`() {
        val payload = setPayload(NoiseCancelling.Off, NcAsmDialect.V1)

        assertContentEquals(hex("6802000102010000"), payload)
        assertEquals(NoiseCancelling.Off, decodeState(payload))
    }

    @Test
    fun `ambient sound round-trips through the V1 layout`() {
        val payload = setPayload(ambient(level = 12, voice = false), NcAsmDialect.V1)

        assertContentEquals(hex("680201010001000c"), payload)
        assertEquals(ambient(level = 12, voice = false), decodeState(payload))
    }

    @Test
    fun `ambient sound on the lowest level uses the wind noise slot`() {
        val payload = setPayload(ambient(level = 0, voice = false), NcAsmDialect.V1)

        // V1 spells level zero as `SINGLE`, the wind noise reduction slot, not as level zero.
        assertContentEquals(hex("6802010101010000"), payload)
        assertEquals(ambient(level = 0, voice = false), decodeState(payload))
    }

    @Test
    fun `focus on voice round-trips through the V1 layout`() {
        val payload = setPayload(ambient(level = 20, voice = true), NcAsmDialect.V1)

        assertEquals(ambient(level = 20, voice = true), decodeState(payload))
    }

    @Test
    fun `cancelling round-trips through the V2 layout`() {
        val payload = setPayload(NoiseCancelling.Cancelling, NcAsmDialect.V2)

        assertContentEquals(hex("68170101000000"), payload)
        assertEquals(NoiseCancelling.Cancelling, decodeState(payload))
    }

    @Test
    fun `off and ambient round-trip through the V2 layout`() {
        val off = setPayload(NoiseCancelling.Off, NcAsmDialect.V2)
        assertContentEquals(hex("68170100010000"), off)
        assertEquals(NoiseCancelling.Off, decodeState(off))

        val ambient = setPayload(ambient(level = 7, voice = true), NcAsmDialect.V2)
        assertContentEquals(hex("68170101010107"), ambient)
        assertEquals(ambient(level = 7, voice = true), decodeState(ambient))
    }

    @Test
    fun `the noise adaptive layout carries its two extra fields untouched`() {
        val preserved = NoiseAdaptiveFields(autoAmbient = true, sensitivity = NoiseAdaptiveSensitivity.High)

        val payload =
            setPayload(ambient(level = 3, voice = false), NcAsmDialect.V2NoiseAdaptive, preserved)

        // The V2 body, then auto-ambient on and sensitivity high.
        assertContentEquals(hex("681901010100030101"), payload)
        assertEquals(ambient(level = 3, voice = false), decodeState(payload))
        assertEquals(preserved, decodeAdaptive(payload))
    }

    @Test
    fun `switching off on V2 keeps the ambient level the device was given`() {
        // A V2 headset reports the level whether or not noise control is on, and takes whatever it
        // is told, so sending zero here would throw away the rider's setting.
        val payload =
            SonyRequest.SetNoiseControl(
                NoiseCancelling.Off,
                ambientLevel = AmbientLevel.clamped(14),
            )
                .payload(capabilities(NcAsmDialect.V2))

        assertContentEquals(hex("6817010001000e"), payload)
    }

    @Test
    fun `a noise adaptive report without its extra fields is refused`() {
        val tooShort = hex("69190101010003")

        assertNull(SonyMessageCodec.decode(frame(tooShort), version = null) as? SonyResponse.NoiseControl)
    }

    @Test
    fun `an unknown inquired type is not read as noise control`() {
        assertNull(
            SonyMessageCodec.decode(frame(hex("69220101010000")), version = null)
                as? SonyResponse.NoiseControl,
        )
    }

    @Test
    fun `an out-of-range ambient level from a device is clamped rather than discarded`() {
        // V1 report with the ambient level byte at 0x40, which is well past the protocol's 0..20.
        val state = decodeState(hex("6902010100010040"))

        assertEquals(20, (state as NoiseCancelling.Ambient).level.steps)
    }

    @Test
    fun `a device mid-adjustment reports itself as unsettled`() {
        // V2 valueChangeStatus 0x00 means the device is still moving to the requested value.
        val response = SonyMessageCodec.decode(frame(hex("69170001010005")), version = null)

        assertEquals(
            SonyResponse.NoiseControl(
                state = NoiseCancelling.Ambient(AmbientLevel.clamped(5), focusOnVoice = false),
                settled = false,
                adaptive = null,
            ),
            response,
        )
    }

    private fun ambient(level: Int, voice: Boolean): NoiseCancelling =
        NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(level)!!, voice)

    private fun setPayload(
        state: NoiseCancelling,
        dialect: NcAsmDialect,
        preserved: NoiseAdaptiveFields = NoiseAdaptiveFields.Default,
    ): ByteArray =
        SonyRequest.SetNoiseControl(state, preserved).payload(capabilities(dialect))

    private fun capabilities(dialect: NcAsmDialect): SonyCapabilities =
        SonyCapabilities(
            version = if (dialect == NcAsmDialect.V1) ProtocolVersion.V1 else ProtocolVersion.V2,
            functionIds =
                when (dialect) {
                    NcAsmDialect.V1 -> setOf(0x62)
                    NcAsmDialect.V2 -> setOf(0x6B)
                    NcAsmDialect.V2NoiseAdaptive -> setOf(0x6D)
                },
        )

    /**
     * A write and the device's report of the same state share one field layout per dialect, and differ
     * only in the command byte, so a write can be read back by relabelling it as the notification the
     * device would send. Anything else would test a layout the device never produces.
     */
    private fun asDeviceReport(setPayload: ByteArray): ByteArray =
        setPayload.copyOf().also { it[0] = NTFY_PARAM }

    private fun decodeState(setPayload: ByteArray): NoiseCancelling =
        report(setPayload).state

    private fun decodeAdaptive(setPayload: ByteArray): NoiseAdaptiveFields? = report(setPayload).adaptive

    /** Noise control reads the same in both generations, so the version is left unknown here. */
    private fun report(setPayload: ByteArray): SonyResponse.NoiseControl =
        SonyMessageCodec.decode(frame(asDeviceReport(setPayload)), version = null)
            as SonyResponse.NoiseControl

    private fun frame(payload: ByteArray): MdrFrame =
        MdrFrame(MdrDataType.DataMdr, 0, payload)

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val NTFY_PARAM: Byte = 0x69
    }
}
