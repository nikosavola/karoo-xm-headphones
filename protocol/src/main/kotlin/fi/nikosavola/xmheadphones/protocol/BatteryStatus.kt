package fi.nikosavola.xmheadphones.protocol

/** What the headset reports about its own battery. */
public data class BatteryStatus(public val percent: Int, public val charging: Boolean) {
    init {
        require(percent in 0..100) { "battery percent is 0..100, was $percent" }
    }

    public companion object {
        /** Some devices report above full. That reads as full rather than as a rejected value. */
        public fun fromDevice(percent: Int, charging: Boolean): BatteryStatus =
            BatteryStatus(percent.coerceIn(0, 100), charging)
    }
}

/** Which battery question a device answers, taken from the functions it advertises. */
public enum class BatteryShape {
    /** One number, the over-ear form. */
    Single,

    /**
     * A level per earbud. Some devices answer only this form, so asking them for the single one gets
     * nothing back at all.
     */
    LeftRight,
}

/** A battery report. A ride screen has room for one number, earbuds answer per bud. */
public sealed interface BatteryReading {
    /** The number to show while riding: the single level, or the bud that runs out first. */
    public val status: BatteryStatus

    public data class Single(override val status: BatteryStatus) : BatteryReading

    public data class LeftRight(public val left: BatteryStatus, public val right: BatteryStatus) :
        BatteryReading {
        override val status: BatteryStatus
            get() = if (left.percent <= right.percent) left else right
    }
}

/**
 * The charging byte as the protocol spells it.
 *
 * Only `0x01` means charging. Everything else reads as not charging, the device's own "unknown"
 * value included, 0xF0 on V1 and 0x02 on V2. With no third state to show while riding, not charging
 * is the answer that is wrong less often.
 */
internal fun chargingFromWire(wire: Int): Boolean = wire == 0x01
