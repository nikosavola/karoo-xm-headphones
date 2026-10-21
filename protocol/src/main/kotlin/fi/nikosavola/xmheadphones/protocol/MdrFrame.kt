package fi.nikosavola.xmheadphones.protocol

/**
 * One MDR packet, markers stripped, body unescaped, checksum verified.
 *
 * Replies to two requests are told apart through [sequence]. The matching [MdrDataType.Ack] carries
 * the other value of the two-value sequence space, not this one.
 */
public class MdrFrame(
    public val type: MdrDataType,
    public val sequence: Int,
    payload: ByteArray,
) {
    /**
     * The bytes after the header.
     *
     * Copied on the way in, so the caller's array cannot change a frame that is already in a set or
     * a map. The array this hands back is the frame's own, and modifying it breaks [equals] and
     * [hashCode], so read it rather than write it.
     */
    public val payload: ByteArray = payload.copyOf()

    init {
        require(sequence in 0..0xFF) { "sequence is one byte, was $sequence" }
    }

    // ByteArray identity would call two identical frames unequal, which reads as a bug everywhere
    // this is compared.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is MdrFrame &&
                type == other.type &&
                sequence == other.sequence &&
                payload.contentEquals(other.payload))

    override fun hashCode(): Int = (type.wire * 31 + sequence) * 31 + payload.contentHashCode()

    override fun toString(): String = "MdrFrame($type, seq=$sequence, ${payload.size} payload bytes)"
}

/** Why a packet was not a usable frame. */
public sealed interface MdrDecodeResult {
    public data class Decoded(public val frame: MdrFrame) : MdrDecodeResult

    /** Shorter than the markers and header require. More bytes may follow. */
    public data object Incomplete : MdrDecodeResult

    /** Missing a marker, or the type byte is outside the table. */
    public data object BadMarker : MdrDecodeResult

    /** An escape byte that is not followed by one of the three escaped values. */
    public data object BadEscape : MdrDecodeResult

    /** The length field disagrees with the bytes actually present. */
    public data object BadLength : MdrDecodeResult

    /** The trailing checksum byte is not the sum of what precedes it. */
    public data object BadChecksum : MdrDecodeResult
}
