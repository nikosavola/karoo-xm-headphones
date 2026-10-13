package fi.nikosavola.xmheadphones.protocol

import kotlin.text.Charsets

private const val ENABLED = 0x00
private const val NO_COMMAND = -1

/**
 * The MDR command layer. Requests become frames, frames become [SonyResponse].
 *
 * Everything the headset sends is untrusted, and nothing here throws on it. A length field is
 * checked before it is used to read, and anything that does not line up comes back as
 * [SonyResponse.Unrecognised] for the session to log and carry on past. A malformed frame must never
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
     * [version] is the generation the handshake settled on, null before then. V1 keeps its preset
     * in slot 0x01, V2 keeps its Clear Bass there, so a 0x01 is a preset on one generation and a
     * bass level on the other. Nothing that arrives before the handshake is ambiguous, so null is
     * allowed.
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
     * V1 answers with a two byte version. V2 answers with a four byte one followed by two table
     * flags. The length is the only thing that tells them apart, so a reply too short for either is
     * refused rather than guessed at.
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
            // Refused rather than guessed. Any other length is not either generation's answer,
            // and a wrong version steers every command that follows.
            else -> null
        }

    private fun deviceInfo(payload: ByteArray): SonyResponse.DeviceInfo? {
        if (payload.size < 2) return null
        val question =
            DeviceInfoQuestion.entries.firstOrNull { it.wire == payload[1].toInt() and 0xFF }
                ?: return null
        // Series and colour come back as two bytes, not a string. Nothing here shows them.
        if (question == DeviceInfoQuestion.SeriesAndColor) {
            return SonyResponse.DeviceInfo(question, value = null)
        }
        // Null is the series reply's spelling of "no string", so a name running past the payload must
        // not read as null. That would pass a truncated reply off as a device with no name, a success
        // even though the headset never answered.
        return readPrefixedString(payload, 2)?.let { SonyResponse.DeviceInfo(question, it) }
    }

    /**
     * V1 lists the functions as a run of bytes, V2 sends each one followed by its order in the
     * table. Length tells the two apart, which keeps this independent of a version this decoder is
     * not told. The pair form is twice as long for the same count.
     */
    private fun supportedFunctions(payload: ByteArray): SonyResponse.SupportedFunctions? {
        if (payload.size < 3) return null
        val count = payload[2].toInt() and 0xFF
        // Exact lengths, not minimums. A reply that lost a byte would pass as the shorter form and
        // read ids out of the following priority bytes, inventing capabilities and enabling
        // features the device never advertised.
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
                    // Some models send the levels with a zero placeholder between them and no
                    // charging byte. That is the earbuds' form, not the headsets'.
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

    /** A level with no charging byte, half an answer. */
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
        // displays, and V2's Clear Bass slot is the one V1 keeps its preset in.
        if (payload[1] != presetSlot) return null
        return SonyResponse.Equalizer(EqualizerPreset.fromWireOrNull(payload[2].toInt() and 0xFF))
    }

    /** How the protocol spells a string. One length byte, then that many UTF-8 bytes. */
    private fun readPrefixedString(payload: ByteArray, offset: Int): String? {
        if (offset >= payload.size) return null
        val length = payload[offset].toInt() and 0xFF
        if (offset + 1 + length > payload.size) return null
        return String(payload, offset + 1, length, Charsets.UTF_8)
    }
}
