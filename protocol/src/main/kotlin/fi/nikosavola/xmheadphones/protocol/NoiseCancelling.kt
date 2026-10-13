package fi.nikosavola.xmheadphones.protocol

/**
 * How loud the headset lets the outside world in.
 *
 * The protocol numbers this 0..20 and every model seen so far reports that range. The value class
 * refuses anything else, so a bad byte never reaches the UI.
 */
@JvmInline
public value class AmbientLevel private constructor(public val steps: Int) {
    public companion object {
        public const val MIN_STEPS: Int = 0
        public const val MAX_STEPS: Int = 20

        public val Silent: AmbientLevel = AmbientLevel(MIN_STEPS)

        public fun fromStepsOrNull(steps: Int): AmbientLevel? =
            steps.takeIf { it in MIN_STEPS..MAX_STEPS }?.let(::AmbientLevel)

        /** Clamps instead of refusing. An out-of-range byte from a device is still a level to show. */
        public fun clamped(steps: Int): AmbientLevel = AmbientLevel(steps.coerceIn(MIN_STEPS, MAX_STEPS))
    }
}

/**
 * The headset's noise control setting, as the Sound Connect app presents it.
 *
 * The two generations encode this differently and neither maps one-to-one onto these three cases.
 * The codecs translate. This is what the rest of the app reasons about.
 */
public sealed interface NoiseCancelling {
    /** Neither cancelling nor listening. The microphones are out of the path. */
    public data object Off : NoiseCancelling

    public data object Cancelling : NoiseCancelling

    public data class Ambient(public val level: AmbientLevel, public val focusOnVoice: Boolean) :
        NoiseCancelling

    public companion object {
        public val Default: NoiseCancelling = Cancelling
    }
}

/** How hard the adaptive noise control reacts to a change in surroundings. */
public enum class NoiseAdaptiveSensitivity(public val wire: Int, public val label: String) {
    Standard(0x00, "Standard"),
    High(0x01, "High"),
    Low(0x02, "Low"),
    ;

    public companion object {
        public fun fromWireOrNull(wire: Int): NoiseAdaptiveSensitivity? =
            entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The two fields only a noise-adaptive V2 device carries. Neither is offered as a control here, so
 * they are kept to stop a mode change resetting them.
 */
public data class NoiseAdaptiveFields(
    public val autoAmbient: Boolean,
    public val sensitivity: NoiseAdaptiveSensitivity,
    /**
     * The byte the device sent when [NoiseAdaptiveSensitivity] has no name for it.
     *
     * The fields are echoed back, not driven. Reading an unknown value as
     * [NoiseAdaptiveSensitivity.Standard] would write Standard over the device's setting, the
     * reset [NoiseAdaptiveFields] exists to avoid.
     */
    public val unknownSensitivity: Int? = null,
) {
    /** The byte to put back on the wire. */
    public val sensitivityWire: Int
        get() = unknownSensitivity ?: sensitivity.wire

    public companion object {
        /** What such a device reports by default. Auto-ambient off, sensitivity standard. */
        public val Default: NoiseAdaptiveFields =
            NoiseAdaptiveFields(autoAmbient = false, sensitivity = NoiseAdaptiveSensitivity.Standard)

        /** The fields a reported byte stands for, keeping it when the enum has no name for it. */
        public fun fromWire(autoAmbient: Boolean, sensitivity: Int): NoiseAdaptiveFields {
            val named = NoiseAdaptiveSensitivity.fromWireOrNull(sensitivity)
            return NoiseAdaptiveFields(
                autoAmbient = autoAmbient,
                sensitivity = named ?: NoiseAdaptiveSensitivity.Standard,
                unknownSensitivity = sensitivity.takeIf { named == null },
            )
        }
    }
}
