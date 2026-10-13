package fi.nikosavola.xmheadphones.protocol

/** A command set a device wants enabled. V1 has table 1 only. A V2 device may offer both. */
public enum class ProtocolTable {
    Table1,
    Table2,
}

/**
 * Something a headset sent us, understood as far as this client takes it.
 *
 * The device answers and acknowledges a request in whichever order it likes. The V1 headset in the
 * committed WH-1000XM3 capture always lands the reply first, so a session matches [Acknowledged] by
 * sequence number, not position.
 */
public sealed interface SonyResponse {
    /** The device accepted a frame we sent. [sequence] is the one it is answering. */
    public data class Acknowledged(public val sequence: Int) : SonyResponse

    public data class ProtocolInfo(
        public val version: ProtocolVersion,
        public val tables: Set<ProtocolTable>,
    ) : SonyResponse

    /** Model name or firmware version, whichever was asked for. */
    public data class DeviceInfo(
        public val question: DeviceInfoQuestion,
        public val value: String?,
    ) : SonyResponse

    public data class SupportedFunctions(public val functionIds: Set<Int>) : SonyResponse

    public data class Battery(public val reading: BatteryReading) : SonyResponse

    /**
     * [settled] is false while the device is still moving to the value it was given, which it
     * reports while an ambient slider is being dragged. This client reports it rather than waits.
     * The acknowledgement confirms the write, and the new state follows as a notification.
     */
    public data class NoiseControl(
        public val state: NoiseCancelling,
        public val settled: Boolean,
        /** Only a noise-adaptive device fills this in. */
        public val adaptive: NoiseAdaptiveFields?,
    ) : SonyResponse

    /** Null when the device reports a preset slot this client has no name for. */
    public data class Equalizer(public val preset: EqualizerPreset?) : SonyResponse

    /** A command this client does not model, kept so a session can log what it skipped. */
    public data class Unrecognised(public val command: Int) : SonyResponse
}
