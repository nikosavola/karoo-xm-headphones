package fi.nikosavola.xmheadphones.protocol

// Field offsets and meanings come from libmdr's ProtocolV1T1.hpp and ProtocolV2T1.hpp structures.
// The decoding half is checked against that project's committed WH-1000XM3 capture, which contains a
// real NC/ASM parameter report and notifications from the button on the headset.

private const val ON: Byte = 0x01
private const val OFF: Byte = 0x00

/** V1 `NcAsmSettingType` and `AsmSettingType`. */
private const val LEVEL_ADJUSTMENT: Byte = 0x01

/** V1 `NcDualSingleValue`: `DUAL` is the noise cancelling slot, `SINGLE` wind noise reduction. */
private const val NC_VALUE_OFF: Byte = 0x00
private const val NC_VALUE_SINGLE: Byte = 0x01
private const val NC_VALUE_DUAL: Byte = 0x02

private const val ASM_ID_NORMAL: Byte = 0x00
private const val ASM_ID_VOICE: Byte = 0x01

/** V2 `ValueChangeStatus`. */
private const val UNDER_CHANGING: Byte = 0x00
private const val CHANGED: Byte = 0x01

/** V2 `NcAsmMode`. */
private const val MODE_NC: Byte = 0x00
private const val MODE_ASM: Byte = 0x01

private const val ASM_MODE_NORMAL: Byte = 0x00
private const val ASM_MODE_VOICE: Byte = 0x01

/** V1 `NcAsmParam`: the inquired type then six fields. */
private const val V1_FIELDS = 6

/** V2 `NcAsmParamModeNcDualModeSwitchAsmSeamless`: the type then five fields. */
private const val V2_FIELDS = 5

/** The same structure plus auto-ambient and adaptive sensitivity: the type then seven fields. */
private const val V2_NOISE_ADAPTIVE_FIELDS = 7

/**
 * The bytes after the inquired type of an `NCASM_RET_PARAM`, `NCASM_NTFY_PARAM` or `NCASM_SET_PARAM`.
 *
 * The three dialects disagree on how the same three rider-facing states are spelled, so the
 * translation lives here and nowhere else. The result starts with the inquired type, so it can be
 * appended straight onto the command byte.
 */
internal fun encodeNcAsmSet(
    state: NoiseCancelling,
    dialect: NcAsmDialect,
    adaptive: NoiseAdaptiveFields,
): ByteArray =
    when (dialect) {
        NcAsmDialect.V1 -> encodeV1(state)
        NcAsmDialect.V2 -> byteArrayOf(dialect.inquiredType) + encodeV2Body(state)
        NcAsmDialect.V2NoiseAdaptive ->
            byteArrayOf(dialect.inquiredType) +
                encodeV2Body(state) +
                byteArrayOf(
                    if (adaptive.autoAmbient) ON else OFF,
                    adaptive.sensitivity.wire.toByte(),
                )
    }

/** Null when the payload is not a noise-control parameter in any dialect we know. */
internal fun decodeNcAsmParam(payload: ByteArray, inquiredType: Byte): SonyResponse.NoiseControl? {
    // The first two bytes are the command and the inquired type; the fields follow.
    val fields = payload.copyOfRange(2, payload.size)
    return when (inquiredType) {
        NcAsmDialect.V1.inquiredType -> decodeV1(fields)
        NcAsmDialect.V2.inquiredType -> decodeV2(fields, noiseAdaptive = false)
        NcAsmDialect.V2NoiseAdaptive.inquiredType -> decodeV2(fields, noiseAdaptive = true)
        else -> null
    }
}

private fun encodeV1(state: NoiseCancelling): ByteArray {
    val enabled = state !is NoiseCancelling.Off
    val level = (state as? NoiseCancelling.Ambient)?.level?.steps
    return byteArrayOf(
        NcAsmDialect.V1.inquiredType,
        if (enabled) ON else OFF,
        LEVEL_ADJUSTMENT,
        // Noise cancelling keeps the "dual" slot, so switching it off leaves the ambient level where
        // the rider put it, which is what the button on the headset does too.
        when {
            state is NoiseCancelling.Cancelling || state is NoiseCancelling.Off -> NC_VALUE_DUAL
            level == AmbientLevel.MIN_STEPS -> NC_VALUE_SINGLE
            else -> NC_VALUE_OFF
        },
        LEVEL_ADJUSTMENT,
        if (state is NoiseCancelling.Ambient && state.focusOnVoice) ASM_ID_VOICE else ASM_ID_NORMAL,
        (level ?: 0).toByte(),
    )
}

private fun encodeV2Body(state: NoiseCancelling): ByteArray {
    val ambient = state as? NoiseCancelling.Ambient
    return byteArrayOf(
        CHANGED,
        if (state is NoiseCancelling.Off) OFF else ON,
        // Off is sent as ambient sound with the whole effect switched off, so a later ambient change
        // only has to move the level.
        if (state is NoiseCancelling.Cancelling) MODE_NC else MODE_ASM,
        if (ambient?.focusOnVoice == true) ASM_MODE_VOICE else ASM_MODE_NORMAL,
        (ambient?.level?.steps ?: 0).toByte(),
    )
}

private fun decodeV1(fields: ByteArray): SonyResponse.NoiseControl? {
    if (fields.size < V1_FIELDS) return null
    val enabled = fields[0] == ON
    val ncValue = fields[2]
    val focusOnVoice = fields[4] == ASM_ID_VOICE
    val level = fields[5].toInt() and 0xFF
    val state =
        when {
            !enabled -> NoiseCancelling.Off
            ncValue == NC_VALUE_DUAL -> NoiseCancelling.Cancelling
            else -> NoiseCancelling.Ambient(AmbientLevel.clamped(level), focusOnVoice)
        }
    return SonyResponse.NoiseControl(state = state, settled = true, adaptive = null)
}

private fun decodeV2(fields: ByteArray, noiseAdaptive: Boolean): SonyResponse.NoiseControl? {
    val expected = if (noiseAdaptive) V2_NOISE_ADAPTIVE_FIELDS else V2_FIELDS
    if (fields.size < expected) return null
    val totalEffect = fields[1]
    val mode = fields[2]
    val ambientMode = fields[3]
    val level = fields[4].toInt() and 0xFF
    val state =
        when {
            totalEffect != ON -> NoiseCancelling.Off
            mode == MODE_NC -> NoiseCancelling.Cancelling
            else ->
                NoiseCancelling.Ambient(AmbientLevel.clamped(level), ambientMode == ASM_MODE_VOICE)
        }
    val adaptive =
        if (!noiseAdaptive) {
            null
        } else {
            NoiseAdaptiveFields(
                autoAmbient = fields[5] == ON,
                sensitivity =
                    NoiseAdaptiveSensitivity.fromWireOrNull(fields[6].toInt() and 0xFF)
                        ?: NoiseAdaptiveSensitivity.Standard,
            )
        }
    return SonyResponse.NoiseControl(
        state = state,
        settled = fields[0] != UNDER_CHANGING,
        adaptive = adaptive,
    )
}
