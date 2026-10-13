package fi.nikosavola.xmheadphones.protocol

// Field offsets and meanings come from libmdr's ProtocolV1T1.hpp and ProtocolV2T1.hpp. The decode
// half is checked against that project's committed WH-1000XM3 capture, which has a real NC/ASM
// parameter report and headset button notifications.

private const val ON: Byte = 0x01
private const val OFF: Byte = 0x00

/** V1 `NcAsmSettingType` and `AsmSettingType`. */
private const val LEVEL_ADJUSTMENT: Byte = 0x01

/** V1 `NcDualSingleValue`. `DUAL` is the noise cancelling slot, `SINGLE` wind noise reduction. */
private const val NC_VALUE_OFF: Byte = 0x00
private const val NC_VALUE_SINGLE: Byte = 0x01
private const val NC_VALUE_DUAL: Byte = 0x02

private const val ASM_ID_NORMAL: Byte = 0x00
private const val ASM_ID_VOICE: Byte = 0x01

/** V2 `ValueChangeStatus`. Only [CHANGED] means the device finished the move. */
private const val CHANGED: Byte = 0x01

/** V2 `NcAsmMode`. */
private const val MODE_NC: Byte = 0x00
private const val MODE_ASM: Byte = 0x01

private const val ASM_MODE_NORMAL: Byte = 0x00
private const val ASM_MODE_VOICE: Byte = 0x01

/** V1 `NcAsmParam`. The inquired type then six fields. */
private const val V1_FIELDS = 6

/** V2 `NcAsmParamModeNcDualModeSwitchAsmSeamless`. The type then five fields. */
private const val V2_FIELDS = 5

/** The same structure plus auto-ambient and adaptive sensitivity. The type then seven fields. */
private const val V2_NOISE_ADAPTIVE_FIELDS = 7

/**
 * The parameter payload of an `NCASM_RET_PARAM`, `NCASM_NTFY_PARAM` or `NCASM_SET_PARAM`.
 *
 * The three dialects spell the same three rider-facing states differently, so the translation lives
 * here. The result starts with the inquired type, so it appends straight onto the command byte.
 */
internal fun encodeNcAsmSet(
    state: NoiseCancelling,
    dialect: NcAsmDialect,
    adaptive: NoiseAdaptiveFields,
    ambientLevel: AmbientLevel,
): ByteArray =
    when (dialect) {
        NcAsmDialect.V1 -> encodeV1(state)
        NcAsmDialect.V2 -> byteArrayOf(dialect.inquiredType) + encodeV2Body(state, ambientLevel)
        NcAsmDialect.V2NoiseAdaptive ->
            byteArrayOf(dialect.inquiredType) +
                encodeV2Body(state, ambientLevel) +
                byteArrayOf(
                    if (adaptive.autoAmbient) ON else OFF,
                    adaptive.sensitivityWire.toByte(),
                )
    }

/** Null when the payload is not a noise-control parameter in any dialect we know. */
internal fun decodeNcAsmParam(payload: ByteArray, inquiredType: Byte): SonyResponse.NoiseControl? {
    // Byte 0 is the command, byte 1 the inquired type. The fields follow.
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
        // the rider put it. The button on the headset does the same.
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

private fun encodeV2Body(state: NoiseCancelling, ambientLevel: AmbientLevel): ByteArray {
    val ambient = state as? NoiseCancelling.Ambient
    return byteArrayOf(
        CHANGED,
        if (state is NoiseCancelling.Off) OFF else ON,
        // Off goes out as ambient sound with the effect switched off, so a later ambient
        // change only moves the level.
        if (state is NoiseCancelling.Cancelling) MODE_NC else MODE_ASM,
        if (ambient?.focusOnVoice == true) ASM_MODE_VOICE else ASM_MODE_NORMAL,
        // The level is sent in every mode, as the reference client does. A headset takes it.
        // Sending zero would lose the rider's setting when noise cancelling switches off.
        (ambient?.level ?: ambientLevel).steps.toByte(),
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
            NoiseAdaptiveFields.fromWire(
                autoAmbient = fields[5] == ON,
                sensitivity = fields[6].toInt() and 0xFF,
            )
        }
    return SonyResponse.NoiseControl(
        state = state,
        // Only the settled value counts as settled. An unknown status is not one this client can claim
        // the device finished with.
        settled = fields[0] == CHANGED,
        adaptive = adaptive,
    )
}
