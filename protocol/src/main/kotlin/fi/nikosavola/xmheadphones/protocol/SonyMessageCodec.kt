package fi.nikosavola.xmheadphones.protocol

import kotlin.text.Charsets

private const val ENABLED = 0x00
private const val NO_COMMAND = -1

/**
 * The MDR command layer: requests to frames, frames to [SonyResponse].
 *
 * Nothing here throws on a device's input. Everything the headset sends is treated as hostile: a
 * length field is checked before it is used to read, and anything that does not line up comes back as
 * [SonyResponse.Unrecognised] so the session can log it and carry on. A malformed frame must never
 * take down a ride.
 */
public object SonyMessageCodec {
    public fun encode(request: SonyHandshakeRequest, sequence: Int): MdrFrame =
        MdrFrame(request.dataType, sequence, request.payload())

    public fun encode(
        request: SonyRequest,
        capabilities: SonyCapabilities,
        sequence: Int,
    ): MdrFrame = MdrFrame(request.dataType, sequence, request.payload(capabilities))

    /**
     * Reads a packet the headset sent.
     *
     * [version] is the generation the session settled on in the handshake, and null before then. It
     * is needed because one reply's meaning genuinely depends on it: V1 puts the preset equalizer in
     * slot 0x01 and V2 puts its Clear Bass there, so a packet with a 0x01 in that position is a preset
     * on one generation and a bass level on the other. Nothing that arrives before the handshake is
     * ambiguous, which is why null is allowed.
     */
    public fun decode(frame: MdrFrame, version: ProtocolVersion?): SonyResponse {
        if (frame.type == MdrDataType.Ack) return SonyResponse.Acknowledged(frame.sequence)
        val payload = frame.payload
        if (payload.isEmpty()) return SonyResponse.Unrecognised(NO_COMMAND)
        val command = payload[0]
        val commandWire = command.toInt() and 0xFF
        return when (command) {
            Connect.RET_PROTOCOL_INFO -> protocolInfo(payload) ?: SonyResponse.Unrecognised(commandWire)
            Connect.RET_DEVICE_INFO -> deviceInfo(payload) ?: SonyResponse.Unrecognised(commandWire)
            Connect.RET_SUPPORT_FUNCTION ->
                supportedFunctions(payload) ?: SonyResponse.Unrecognised(commandWire)
            BatteryCommands.V1_RET,
            BatteryCommands.V1_NTFY,
            BatteryCommands.V2_RET,
            BatteryCommands.V2_NTFY -> battery(payload) ?: SonyResponse.Unrecognised(commandWire)
            NcAsm.RET_PARAM,
            NcAsm.NTFY_PARAM -> noiseControl(payload) ?: SonyResponse.Unrecognised(commandWire)
            EqualizerCommands.RET_PARAM,
            EqualizerCommands.NTFY_PARAM ->
                equalizer(payload, version) ?: SonyResponse.Unrecognised(commandWire)
            else -> SonyResponse.Unrecognised(commandWire)
        }
    }

    /**
     * V1 answers with a two byte version, V2 with a four byte one followed by two table flags. The
     * length is the only thing that tells them apart, so a reply too short for either is refused
     * rather than guessed at.
     */
    private fun protocolInfo(payload: ByteArray): SonyResponse.ProtocolInfo? =
        when (payload.size) {
            // Four byte version and one flag per command table.
            8 ->
                SonyResponse.ProtocolInfo(
                    version = ProtocolVersion.V2,
                    tables =
                        buildSet {
                            if (payload[6].toInt() and 0xFF == ENABLED) add(ProtocolTable.Table1)
                            if (payload[7].toInt() and 0xFF == ENABLED) add(ProtocolTable.Table2)
                        },
                )
            4 -> SonyResponse.ProtocolInfo(ProtocolVersion.V1, setOf(ProtocolTable.Table1))
            // Refused rather than guessed at: a reply of any other length is not either generation's
            // answer, and settling the version wrongly decides every command that follows.
            else -> null
        }

    private fun deviceInfo(payload: ByteArray): SonyResponse.DeviceInfo? {
        if (payload.size < 2) return null
        val question =
            DeviceInfoQuestion.entries.firstOrNull { it.wire == payload[1].toInt() and 0xFF }
                ?: return null
        // Series and colour come back as two bytes rather than a string, and nothing here shows them.
        if (question == DeviceInfoQuestion.SeriesAndColor) {
            return SonyResponse.DeviceInfo(question, value = null)
        }
        return SonyResponse.DeviceInfo(question, readPrefixedString(payload, 2))
    }

    /**
     * V1 lists the functions as a run of bytes; V2 sends each one followed by its order in the table.
     * The reply's length is what tells the two apart, which keeps this independent of a version the
     * decoder is not told: the pair form is twice as long for the same count.
     */
    private fun supportedFunctions(payload: ByteArray): SonyResponse.SupportedFunctions? {
        if (payload.size < 3) return null
        val count = payload[2].toInt() and 0xFF
        // Exact lengths, not at least: a reply that lost a byte would otherwise pass the shorter form
        // and have the function ids read out of the following priority bytes, which invents a
        // capability list and enables features the device never advertised.
        return when (payload.size) {
            3 + count * 2 ->
                SonyResponse.SupportedFunctions(
                    (0 until count).map { payload[3 + it * 2].toInt() and 0xFF }.toSet(),
                )
            3 + count ->
                SonyResponse.SupportedFunctions(
                    (0 until count).map { payload[3 + it].toInt() and 0xFF }.toSet(),
                )
            else -> null
        }
    }

    private fun battery(payload: ByteArray): SonyResponse.Battery? {
        if (payload.size < 4) return null
        return when (payload[1]) {
            BatteryInquiry.SINGLE -> SonyResponse.Battery(BatteryReading.Single(batteryStatus(payload, 2)))
            BatteryInquiry.LEFT_RIGHT ->
                when {
                    // A charging byte after each level.
                    payload.size >= 6 ->
                        SonyResponse.Battery(
                            BatteryReading.LeftRight(
                                left = batteryStatus(payload, 2),
                                right = batteryStatus(payload, 4),
                            ),
                        )
                    // Some models report the levels with a zero placeholder between them and no
                    // charging byte at all, which is the earbuds' form rather than the headsets'.
                    payload.size == 5 ->
                        SonyResponse.Battery(
                            BatteryReading.LeftRight(
                                left = levelOnly(payload, 2),
                                right = levelOnly(payload, 4),
                            ),
                        )
                    else -> null
                }
            // The cradle and threshold forms carry fields this client does not ask for.
            else -> null
        }
    }

    private fun batteryStatus(payload: ByteArray, offset: Int): BatteryStatus =
        BatteryStatus.fromDevice(
            percent = payload[offset].toInt() and 0xFF,
            charging = chargingFromWire(payload[offset + 1].toInt() and 0xFF),
        )

    /** A level with no charging byte to go with it; the device reported only half the answer. */
    private fun levelOnly(payload: ByteArray, offset: Int): BatteryStatus =
        BatteryStatus.fromDevice(payload[offset].toInt() and 0xFF, charging = false)

    private fun noiseControl(payload: ByteArray): SonyResponse.NoiseControl? {
        if (payload.size < 3) return null
        return decodeNcAsmParam(payload, payload[1])
    }

    private fun equalizer(
        payload: ByteArray,
        version: ProtocolVersion?,
    ): SonyResponse.Equalizer? {
        if (payload.size < 3) return null
        // Nothing the equalizer says can be read before the generation is known.
        val presetSlot = version?.let(::presetEqType) ?: return null
        // Only the preset form is read. The Clear Bass and custom-band forms carry curves nothing
        // displays, and on V2 the Clear Bass slot is the one V1 keeps its preset in.
        if (payload[1] != presetSlot) return null
        return SonyResponse.Equalizer(EqualizerPreset.fromWireOrNull(payload[2].toInt() and 0xFF))
    }

    /** A one byte length then that many UTF-8 bytes, which is how the protocol spells a string. */
    private fun readPrefixedString(payload: ByteArray, offset: Int): String? {
        if (offset >= payload.size) return null
        val length = payload[offset].toInt() and 0xFF
        if (offset + 1 + length > payload.size) return null
        return String(payload, offset + 1, length, Charsets.UTF_8)
    }
}
