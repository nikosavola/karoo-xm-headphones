package fi.nikosavola.xmheadphones.protocol

/**
 * An equalizer preset the Sound Connect app offers.
 *
 * The wire ids match in both protocol generations. The `RESERVED_FOR_FUTURE_*` slots stay out
 * deliberately. A device sending one reports a preset this client has no name for.
 */
public enum class EqualizerPreset(public val wire: Int, public val label: String) {
    Off(0x00, "Off"),
    Rock(0x01, "Rock"),
    Pop(0x02, "Pop"),
    Jazz(0x03, "Jazz"),
    Dance(0x04, "Dance"),
    Edm(0x05, "EDM"),
    HipHop(0x06, "R&B / Hip-hop"),
    Acoustic(0x07, "Acoustic"),
    Bright(0x10, "Bright"),
    Excited(0x11, "Excited"),
    Mellow(0x12, "Mellow"),
    Relaxed(0x13, "Relaxed"),
    Vocal(0x14, "Vocal"),
    Treble(0x15, "Treble boost"),
    Bass(0x16, "Bass boost"),
    Speech(0x17, "Speech"),
    Custom(0xA0, "Custom"),
    UserSetting1(0xA1, "User setting 1"),
    UserSetting2(0xA2, "User setting 2"),
    UserSetting3(0xA3, "User setting 3"),
    UserSetting4(0xA4, "User setting 4"),
    UserSetting5(0xA5, "User setting 5"),
    ;

    public companion object {
        /** Null for a slot we have no name for, so the caller reports it rather than mislabels it. */
        public fun fromWireOrNull(wire: Int): EqualizerPreset? =
            entries.firstOrNull { it.wire == wire }
    }
}
