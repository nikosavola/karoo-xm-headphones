package fi.nikosavola.xmheadphones.protocol

/**
 * How loud the headset lets the outside world in.
 *
 * The protocol numbers this 0..20 and every model we have seen reports the same range, so the
 * value class refuses anything else rather than letting a bad byte reach the UI.
 */
@JvmInline
public value class AmbientLevel private constructor(public val steps: Int) {
    public companion object {
        public const val MIN_STEPS: Int = 0
        public const val MAX_STEPS: Int = 20

        public val Silent: AmbientLevel = AmbientLevel(MIN_STEPS)

        public fun fromStepsOrNull(steps: Int): AmbientLevel? =
            steps.takeIf { it in MIN_STEPS..MAX_STEPS }?.let(::AmbientLevel)

        /** Clamps instead of refusing: an out-of-range byte from a device is still a level to show. */
        public fun clamped(steps: Int): AmbientLevel = AmbientLevel(steps.coerceIn(MIN_STEPS, MAX_STEPS))
    }
}

/**
 * The headset's noise control setting, as the Sound Connect app presents it.
 *
 * The two protocol generations encode this differently and neither maps one-to-one onto these three
 * cases; the codecs do that translation, and this is what the rest of the app reasons about.
 */
public sealed interface NoiseCancelling {
    /** Neither cancelling nor listening: the microphones are out of the path. */
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
 * The two fields only a noise-adaptive V2 device carries. This client does not offer either control,
 * so it keeps them so a mode change does not quietly reset them.
 */
public data class NoiseAdaptiveFields(
    public val autoAmbient: Boolean,
    public val sensitivity: NoiseAdaptiveSensitivity,
) {
    public companion object {
        /** Auto-ambient off, sensitivity standard, which is what such a device reports by default. */
        public val Default: NoiseAdaptiveFields =
            NoiseAdaptiveFields(autoAmbient = false, sensitivity = NoiseAdaptiveSensitivity.Standard)
    }
}
