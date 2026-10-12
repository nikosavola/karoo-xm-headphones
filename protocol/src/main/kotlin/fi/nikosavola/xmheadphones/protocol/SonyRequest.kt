package fi.nikosavola.xmheadphones.protocol

/**
 * The CONNECT commands. A session sends these first, before it knows what the headset can do, which
 * is why they carry no [SonyCapabilities].
 */
public sealed interface SonyHandshakeRequest {
    public val dataType: MdrDataType

    /** Everything after the frame header. */
    public fun payload(): ByteArray

    public data object ProtocolInfo : SonyHandshakeRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(): ByteArray = byteArrayOf(Connect.PROTOCOL_INFO, FIXED_VALUE)
    }

    public data class DeviceInfo(public val question: DeviceInfoQuestion) : SonyHandshakeRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(): ByteArray = byteArrayOf(Connect.DEVICE_INFO, question.wire.toByte())
    }

    public data object SupportedFunctions : SonyHandshakeRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(): ByteArray = byteArrayOf(Connect.SUPPORT_FUNCTION, FIXED_VALUE)
    }
}

/**
 * A request made once the handshake has settled. [payload] takes the negotiated capabilities because
 * writing noise control has to use the parameter set the device answered the handshake with.
 */
public sealed interface SonyRequest {
    public val dataType: MdrDataType

    public fun payload(capabilities: SonyCapabilities): ByteArray

    /**
     * Whether the headset advertised what this asks for.
     *
     * [payload] refuses to build a request a headset cannot answer, so a caller has to ask this
     * first. Keeping it on the request means a new one cannot be added without answering the
     * question, which is what stops a missing check turning into a thrown exception mid-ride.
     */
    public fun supportedBy(capabilities: SonyCapabilities): Boolean

    public data object Battery : SonyRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(capabilities: SonyCapabilities): ByteArray {
            val shape =
                requireNotNull(capabilities.batteryShape) { "no battery function was advertised" }
            val inquiredType =
                if (shape == BatteryShape.LeftRight) BatteryInquiry.LEFT_RIGHT else BatteryInquiry.SINGLE
            return byteArrayOf(batteryGetCommand(capabilities.version), inquiredType)
        }

        override fun supportedBy(capabilities: SonyCapabilities): Boolean =
            capabilities.batteryShape != null
    }

    public data object NoiseControl : SonyRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(capabilities: SonyCapabilities): ByteArray =
            byteArrayOf(
                NcAsm.GET_PARAM,
                requireNotNull(capabilities.noiseCancelling) { "noise control was not advertised" }
                    .inquiredType,
            )

        override fun supportedBy(capabilities: SonyCapabilities): Boolean =
            capabilities.noiseCancelling != null
    }

    public data class SetNoiseControl(
        public val state: NoiseCancelling,
        /**
         * Only a noise-adaptive V2 device reads these: this client does not offer the auto-ambient or
         * sensitivity controls, so it echoes back what the device last reported.
         */
        public val preservedAdaptive: NoiseAdaptiveFields = NoiseAdaptiveFields.Default,
    ) : SonyRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(capabilities: SonyCapabilities): ByteArray {
            val dialect =
                requireNotNull(capabilities.noiseCancelling) { "noise control was not advertised" }
            return byteArrayOf(NcAsm.SET_PARAM) + encodeNcAsmSet(state, dialect, preservedAdaptive)
        }

        override fun supportedBy(capabilities: SonyCapabilities): Boolean =
            capabilities.noiseCancelling != null
    }

    public data object Equalizer : SonyRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(capabilities: SonyCapabilities): ByteArray =
            byteArrayOf(EqualizerCommands.GET_PARAM, EqualizerCommands.PRESET_EQ)

        override fun supportedBy(capabilities: SonyCapabilities): Boolean =
            capabilities.supportsEqualizerPreset
    }

    public data class SetEqualizer(public val preset: EqualizerPreset) : SonyRequest {
        override val dataType: MdrDataType = MdrDataType.DataMdr

        override fun payload(capabilities: SonyCapabilities): ByteArray =
            // Band steps are left out on purpose: a device sent a preset and band steps in one frame
            // drops both, so a preset change carries a preset and nothing else.
            byteArrayOf(
                EqualizerCommands.SET_PARAM,
                EqualizerCommands.PRESET_EQ,
                preset.wire.toByte(),
                NO_BANDS,
            )

        override fun supportedBy(capabilities: SonyCapabilities): Boolean =
            capabilities.supportsEqualizerPreset
    }
}

private const val FIXED_VALUE: Byte = 0x00
private const val NO_BANDS: Byte = 0x00

/**
 * The `COMMON_GET_BATTERY_LEVEL` of V1 and the `POWER_GET_STATUS` of V2. V1 folds battery, charging
 * and power saving together, so V2 moved it into a group of its own.
 */
internal fun batteryGetCommand(version: ProtocolVersion): Byte =
    when (version) {
        ProtocolVersion.V1 -> 0x10
        ProtocolVersion.V2 -> 0x22
    }

internal object Connect {
    const val PROTOCOL_INFO: Byte = 0x00
    const val CAPABILITY_INFO: Byte = 0x02
    const val DEVICE_INFO: Byte = 0x04
    const val SUPPORT_FUNCTION: Byte = 0x06

    const val RET_PROTOCOL_INFO: Byte = 0x01
    const val RET_DEVICE_INFO: Byte = 0x05
    const val RET_SUPPORT_FUNCTION: Byte = 0x07
}

/** `BatteryInquiredType` in V1 and `PowerInquiredType` in V2 agree on the first three values. */
internal object BatteryInquiry {
    const val SINGLE: Byte = 0x00
    const val LEFT_RIGHT: Byte = 0x01
    const val CRADLE: Byte = 0x02
}

/** The NCASM command bytes are the same in both command tables. */
internal object NcAsm {
    const val GET_STATUS: Byte = 0x62
    const val RET_STATUS: Byte = 0x63
    const val NTFY_STATUS: Byte = 0x65
    const val GET_PARAM: Byte = 0x66
    const val RET_PARAM: Byte = 0x67
    const val SET_PARAM: Byte = 0x68
    const val NTFY_PARAM: Byte = 0x69
}

internal object EqualizerCommands {
    const val RET_STATUS: Byte = 0x53
    const val GET_PARAM: Byte = 0x56
    const val RET_PARAM: Byte = 0x57
    const val SET_PARAM: Byte = 0x58
    const val NTFY_PARAM: Byte = 0x59

    /** V1 and V2 both use 0x00 for the preset slot. */
    const val PRESET_EQ: Byte = 0x00
}

internal object BatteryCommands {
    const val V1_RET: Byte = 0x11
    const val V1_NTFY: Byte = 0x13
    const val V2_RET: Byte = 0x23
    const val V2_NTFY: Byte = 0x25
}
