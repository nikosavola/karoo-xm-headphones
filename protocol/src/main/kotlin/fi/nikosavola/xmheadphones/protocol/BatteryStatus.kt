package fi.nikosavola.xmheadphones.protocol

/** What the headset reports about its own battery. */
public data class BatteryStatus(public val percent: Int, public val charging: Boolean) {
    init {
        require(percent in 0..100) { "battery percent is 0..100, was $percent" }
    }

    public companion object {
        /** Devices report above full; that reads as full rather than as a rejected value. */
        public fun fromDevice(percent: Int, charging: Boolean): BatteryStatus =
            BatteryStatus(percent.coerceIn(0, 100), charging)
    }
}

/** Which battery question a device is willing to answer, read off the functions it advertises. */
public enum class BatteryShape {
    /** One number: over-ear headsets. */
    Single,

    /** A level per earbud; asking for the single form gets no answer at all. */
    LeftRight,
}

/** A battery report. Earbuds answer per bud, and a ride screen has room for one number. */
public sealed interface BatteryReading {
    public data class Single(public val status: BatteryStatus) : BatteryReading

    public data class LeftRight(public val left: BatteryStatus, public val right: BatteryStatus) :
        BatteryReading {
        /** The bud that runs out first, which is the number worth showing while riding. */
        public val lowest: BatteryStatus
            get() = if (left.percent <= right.percent) left else right
    }
}

/** The charging byte as the protocol spells it. The unknown value is 0xF0 in V1 and 0x02 in V2. */
internal fun chargingFromWire(wire: Int): Boolean = wire == 0x01
