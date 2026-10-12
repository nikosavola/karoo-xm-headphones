package fi.nikosavola.xmheadphones.protocol

/**
 * The `type` byte of an MDR packet.
 *
 * A headset session only ever exchanges [DataMdr] and [Ack] on table 1, and [DataMdrNo2] on table 2.
 * The rest of the table is kept so a packet we did not expect is named in a log line instead of
 * showing up as a bare number.
 */
public enum class MdrDataType(public val wire: Int) {
    Data(0x00),
    Ack(0x01),
    DataMcNo1(0x02),
    DataIcd(0x09),
    DataEv(0x0A),
    DataMdr(0x0C),
    DataCommon(0x0D),
    DataMdrNo2(0x0E),
    Shot(0x10),
    ShotMcNo1(0x12),
    ShotIcd(0x19),
    ShotEv(0x1A),
    ShotMdr(0x1C),
    ShotCommon(0x1D),
    ShotMdrNo2(0x1E),
    LargeDataCommon(0x2D),
    ;

    public companion object {
        /** Null for a type byte outside the table, which the caller reports rather than guesses. */
        public fun fromWire(wire: Int): MdrDataType? = entries.firstOrNull { it.wire == wire }
    }
}
