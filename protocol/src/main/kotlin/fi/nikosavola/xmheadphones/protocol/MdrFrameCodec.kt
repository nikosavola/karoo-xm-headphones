package fi.nikosavola.xmheadphones.protocol

/**
 * The MDR wire framing.
 *
 * A packet is a start marker, an escaped body, and an end marker. The body is
 * `type(1) sequence(1) length(4, big endian) payload(length) checksum(1)`, where the checksum is the
 * byte sum of everything before it in the body. Escaping applies to the body as a whole, checksum
 * included, and only to the three bytes that would otherwise be mistaken for a marker:
 * `0x3C`, `0x3D` and `0x3E` become `0x3D` followed by themselves minus `0x10`.
 *
 * The layout is the one libmdr implements (`libmdr/src/Command.cpp`, `MDRPackCommand`); every packet
 * of the committed WH-1000XM3 capture round-trips through this codec.
 */
public object MdrFrameCodec {
    public const val START_MARKER: Int = 0x3E
    public const val END_MARKER: Int = 0x3C

    private const val ESCAPE_MARKER = 0x3D
    private const val ESCAPED_LOW = 0x2C
    private const val ESCAPED_HIGH = 0x2E
    private const val ESCAPE_SUBTRACT = 0x10

    private const val HEADER_BYTES = 6

    /** The protocol carries no more than this in one packet; libmdr chunks above it. */
    public const val MAX_PAYLOAD_BYTES: Int = 2048

    /** Wrap [frame] for the wire, markers included. */
    public fun encode(frame: MdrFrame): ByteArray {
        require(frame.payload.size <= MAX_PAYLOAD_BYTES) {
            "payload of ${frame.payload.size} bytes exceeds the $MAX_PAYLOAD_BYTES byte packet limit"
        }
        val body = ByteArray(HEADER_BYTES + frame.payload.size + 1)
        body[0] = frame.type.wire.toByte()
        body[1] = frame.sequence.toByte()
        writeIntBigEndian(body, 2, frame.payload.size)
        frame.payload.copyInto(body, HEADER_BYTES)
        body[body.lastIndex] = checksum(body, 0, body.lastIndex).toByte()

        val escaped = escape(body)
        return ByteArray(escaped.size + 2).also {
            it[0] = START_MARKER.toByte()
            escaped.copyInto(it, 1)
            it[it.lastIndex] = END_MARKER.toByte()
        }
    }

    /**
     * Parse exactly one packet. [bytes] is either a whole packet or a prefix of one, so a truncated
     * read is [MdrDecodeResult.Incomplete] rather than an error.
     */
    public fun decode(bytes: ByteArray): MdrDecodeResult {
        if (bytes.size < 2) return MdrDecodeResult.Incomplete
        if (bytes[0].toInt() and 0xFF != START_MARKER) return MdrDecodeResult.BadMarker
        if (bytes[bytes.lastIndex].toInt() and 0xFF != END_MARKER) return MdrDecodeResult.Incomplete

        val body = unescape(bytes, 1, bytes.lastIndex) ?: return MdrDecodeResult.BadEscape
        // type, sequence, four length bytes, and a checksum.
        if (body.size < HEADER_BYTES + 1) return MdrDecodeResult.Incomplete

        val type = MdrDataType.fromWire(body[0].toInt() and 0xFF) ?: return MdrDecodeResult.BadMarker
        val sequence = body[1].toInt() and 0xFF
        val length = readIntBigEndian(body, 2)
        if (length < 0 || body.size != HEADER_BYTES + length + 1) return MdrDecodeResult.BadLength

        val expected = checksum(body, 0, body.lastIndex)
        val actual = body[body.lastIndex].toInt() and 0xFF
        if (expected != actual) return MdrDecodeResult.BadChecksum

        return MdrDecodeResult.Decoded(
            MdrFrame(type, sequence, body.copyOfRange(HEADER_BYTES, HEADER_BYTES + length)),
        )
    }

    private fun escape(body: ByteArray): ByteArray {
        val out = ByteArray(body.size * 2)
        var n = 0
        for (byte in body) {
            val value = byte.toInt() and 0xFF
            if (value in ESCAPED_LOW + ESCAPE_SUBTRACT..ESCAPED_HIGH + ESCAPE_SUBTRACT) {
                out[n++] = ESCAPE_MARKER.toByte()
                out[n++] = (value - ESCAPE_SUBTRACT).toByte()
            } else {
                out[n++] = byte
            }
        }
        return out.copyOf(n)
    }

    /** Null when an escape marker is not followed by one of the three escaped values. */
    private fun unescape(bytes: ByteArray, from: Int, until: Int): ByteArray? {
        val out = ByteArray(until - from)
        var n = 0
        var i = from
        while (i < until) {
            val value = bytes[i].toInt() and 0xFF
            i++
            if (value != ESCAPE_MARKER) {
                out[n++] = value.toByte()
                continue
            }
            if (i >= until) return null
            val escaped = bytes[i].toInt() and 0xFF
            i++
            if (escaped !in ESCAPED_LOW..ESCAPED_HIGH) return null
            out[n++] = (escaped + ESCAPE_SUBTRACT).toByte()
        }
        return out.copyOf(n)
    }

    private fun checksum(bytes: ByteArray, from: Int, until: Int): Int {
        var sum = 0
        for (i in from until until) sum += bytes[i].toInt() and 0xFF
        return sum and 0xFF
    }

    private fun writeIntBigEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readIntBigEndian(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF shl 24) or
            (source[offset + 1].toInt() and 0xFF shl 16) or
            (source[offset + 2].toInt() and 0xFF shl 8) or
            (source[offset + 3].toInt() and 0xFF)
}
