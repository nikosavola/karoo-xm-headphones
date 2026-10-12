package fi.nikosavola.xmheadphones.protocol

/**
 * Which noise-control parameter set a headset speaks.
 *
 * The three are not variants of one layout: V1 packs the mode into the "dual/single/off" byte of a
 * seven byte parameter, V2 has a real mode byte, and the adaptive variant adds two fields. Which one
 * applies is read off the functions the device advertises, not guessed from its model name.
 */
public enum class NcAsmDialect(public val inquiredType: Byte) {
    /** V1 table 1, `NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE`. */
    V1(0x02),

    /** V2, `MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS` (0x17). */
    V2(0x17),

    /**
     * V2, `..._NOISE_ADAPTATION` (0x19). Identical to [V2] for the first five bytes and then carries
     * auto-ambient and noise-adaptive sensitivity, which this client preserves rather than drives.
     */
    V2NoiseAdaptive(0x19),
}

/**
 * What a headset advertised in the handshake.
 *
 * Held as the raw function ids the device sent, with the questions we actually ask answered from
 * them, so an unknown id costs nothing and a new one needs no parser change.
 */
public data class SonyCapabilities(
    public val version: ProtocolVersion,
    public val functionIds: Set<Int>,
) {
    /**
     * Null when the device advertises no battery function. Earbuds answer per bud and ignore the
     * single-battery question, so the shape has to be taken from what the device offered.
     */
    public val batteryShape: BatteryShape?
        get() =
            BATTERY_FUNCTIONS.getValue(version)
                .firstOrNull { (functionId, _) -> functionId in functionIds }
                ?.second

    public val supportsEqualizerPreset: Boolean
        get() = functionIds.any { it in EQ_FUNCTIONS[version].orEmpty() }

    /** Null when the device advertises no noise-control function this client can drive. */
    public val noiseCancelling: NcAsmDialect?
        get() =
            NC_FUNCTIONS.getValue(version)
                .firstOrNull { (functionId, _) -> functionId in functionIds }
                ?.second

    internal companion object {
        private val BATTERY_FUNCTIONS =
            mapOf(
                ProtocolVersion.V1 to
                    listOf(0x11 to BatteryShape.Single, 0x15 to BatteryShape.LeftRight),
                ProtocolVersion.V2 to
                    listOf(0x20 to BatteryShape.Single, 0x21 to BatteryShape.LeftRight),
            )

        private val EQ_FUNCTIONS =
            mapOf(
                ProtocolVersion.V1 to setOf(0x51, 0x53),
                ProtocolVersion.V2 to setOf(0x50, 0x52, 0x53),
            )

        // Ordered most capable first, so a device advertising several is driven through the one with
        // the most fields rather than whichever byte happens to come first. Only the two this codec
        // encodes are listed: a device advertising the 0x16 or 0x18 shapes instead reports no
        // noise-control support rather than being written in a shape it would accept and then ignore.
        private val NC_FUNCTIONS =
            mapOf(
                ProtocolVersion.V1 to
                    listOf(0x62 to NcAsmDialect.V1, 0x61 to NcAsmDialect.V1, 0x63 to NcAsmDialect.V1),
                ProtocolVersion.V2 to
                    listOf(0x6D to NcAsmDialect.V2NoiseAdaptive, 0x6B to NcAsmDialect.V2),
            )
    }
}
