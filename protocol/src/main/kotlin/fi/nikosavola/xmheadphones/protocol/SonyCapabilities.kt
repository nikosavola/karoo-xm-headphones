package fi.nikosavola.xmheadphones.protocol

/**
 * Which noise-control parameter set a headset speaks.
 *
 * Not variants of one layout. V1 packs the mode into the "dual/single/off" byte of a seven byte
 * parameter, V2 has its own mode byte, the adaptive variant adds two fields. Which applies comes
 * from the functions the device advertises, not its model name.
 */
public enum class NcAsmDialect(public val inquiredType: Byte) {
    /** V1 table 1, `NOISE_CANCELLING_AND_AMBIENT_SOUND_MODE`. */
    V1(0x02),

    /** V2, `MODE_NC_ASM_DUAL_NC_MODE_SWITCH_AND_ASM_SEAMLESS` (0x17). */
    V2(0x17),

    /**
     * V2, `..._NOISE_ADAPTATION`, 0x19. Identical to [V2] for the first five bytes, then
     * auto-ambient and noise-adaptive sensitivity, which this client preserves rather than drives.
     */
    V2NoiseAdaptive(0x19),
}

/**
 * What a headset advertised in the handshake.
 *
 * Held as the raw function ids the device sent. The questions we actually ask are answered from
 * them, so an unknown id costs nothing and a new one needs no parser change.
 */
public data class SonyCapabilities(
    public val version: ProtocolVersion,
    public val functionIds: Set<Int>,
    /**
     * The command tables the headset says it answers on.
     *
     * Kept because it is the headset's own account of which half of the protocol it speaks, and it
     * is the first thing to look at when a device answers the handshake and then nothing else. The
     * ids in [functionIds] are numbered within these, so the same number means different commands
     * depending on which are open.
     *
     * No default, deliberately. This is published for other clients, and a default would let a
     * caller leave it out and get a capabilities set indistinguishable from a headset that reported
     * both tables switched off. A caller that does not know passes an empty set and says so.
     */
    public val tables: Set<ProtocolTable>,
) {
    /**
     * Null when the device advertises no battery function. Earbuds answer per bud, and some ignore
     * the single-battery question altogether, so the shape follows what the device offered.
     */
    public val batteryShape: BatteryShape?
        get() = BATTERY_FUNCTIONS.firstOrNull { (functionId, _) -> functionId in functionIds }?.second

    public val supportsEqualizerPreset: Boolean
        get() = functionIds.any { it in EQ_FUNCTIONS[version].orEmpty() }

    /** Null when the device advertises no noise-control function this client can drive. */
    public val noiseCancelling: NcAsmDialect?
        get() =
            NC_FUNCTIONS.getValue(version)
                .firstOrNull { (functionId, _) -> functionId in functionIds }
                ?.second

    internal companion object {
        /**
         * The two battery indicators, single before left/right.
         *
         * One list for both protocol versions. The ids belong to the command table the handshake
         * reads rather than to the version, and only the command byte and the reply layout differ
         * between the two. Measured: a WH-1000XM3 reports 0x11 alone, a WF-1000XM5 reports both and
         * answers whichever is asked.
         *
         * Single goes first on purpose. A device reporting both is then asked the single question,
         * because a ride screen has room for one number either way. Left/right is what the devices
         * that answer nothing else are driven through.
         */
        private val BATTERY_FUNCTIONS =
            listOf(0x11 to BatteryShape.Single, 0x15 to BatteryShape.LeftRight)

        private val EQ_FUNCTIONS =
            mapOf(
                ProtocolVersion.V1 to setOf(0x51, 0x53),
                ProtocolVersion.V2 to setOf(0x50, 0x52, 0x53),
            )

        // Most capable first, so a device advertising several is driven through the one with the most
        // fields, not whichever byte comes first. Only the dialects this codec encodes are listed.
        // V2's 0x16 and 0x18, V1's 0x61 with noise cancelling but no ambient and 0x63 with ambient
        // but no noise cancelling, describe the same three states in their own layouts under their
        // own inquired types. Listing a layout we cannot encode would write a shape the device
        // accepts and then ignores, so such devices report no noise-control support instead.
        private val NC_FUNCTIONS =
            mapOf(
                ProtocolVersion.V1 to listOf(0x62 to NcAsmDialect.V1),
                ProtocolVersion.V2 to
                    listOf(0x6D to NcAsmDialect.V2NoiseAdaptive, 0x6B to NcAsmDialect.V2),
            )
    }
}
