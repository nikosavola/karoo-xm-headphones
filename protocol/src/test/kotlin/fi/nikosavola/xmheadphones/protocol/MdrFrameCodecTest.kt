package fi.nikosavola.xmheadphones.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The packets here are real. They were captured from a WH-1000XM3 on firmware 4.5.2 and committed to
 * mos9527/SonyHeadphonesClient under `tests/WH-1000XM3-4.5.2/`. Encoding must reproduce them byte for
 * byte, not merely round-trip. A wrong checksum or escaping would hide happily inside a symmetric
 * test.
 */
class MdrFrameCodecTest {

    @Test
    fun `encoding the protocol info request reproduces the captured byte sequence`() {
        val frame = MdrFrame(MdrDataType.DataMdr, 0, byteArrayOf(0x01, 0x00, 0x40, 0x10))

        assertContentEquals(
            hex("3e0c000000000401004010613c"),
            MdrFrameCodec.encode(frame),
        )
    }

    @Test
    fun `encoding an acknowledgement reproduces the captured byte sequence`() {
        val frame = MdrFrame(MdrDataType.Ack, 1, ByteArray(0))

        assertContentEquals(hex("3e010100000000023c"), MdrFrameCodec.encode(frame))
    }

    @Test
    fun `the model name reply decodes to its command and payload`() {
        val decoded = MdrFrameCodec.decode(hex("3e0c000000000d05010a57482d31303030584d338e3c"))

        val frame = (decoded as MdrDecodeResult.Decoded).frame
        assertEquals(MdrDataType.DataMdr, frame.type)
        assertEquals(0, frame.sequence)
        assertContentEquals(hex("05010a57482d31303030584d33"), frame.payload)
    }

    @Test
    fun `marker bytes inside a payload survive escaping`() {
        val frame = MdrFrame(MdrDataType.DataMdr, 7, byteArrayOf(0x3C, 0x3D, 0x3E, 0x00))

        val encoded = MdrFrameCodec.encode(frame)

        assertEquals(MdrFrameCodec.START_MARKER.toByte(), encoded[0])
        assertEquals(MdrFrameCodec.END_MARKER.toByte(), encoded.last())
        // The header is six bytes, so the payload starts at index seven, and each of the three marker
        // bytes becomes the escape byte then itself minus 0x10.
        assertContentEquals(
            hex("3d2c3d2d3d2e"),
            encoded.copyOfRange(7, 13),
        )
        assertEquals(MdrDecodeResult.Decoded(frame), MdrFrameCodec.decode(encoded))
    }

    @Test
    fun `a flipped bit in the checksum is rejected`() {
        val encoded = MdrFrameCodec.encode(MdrFrame(MdrDataType.DataMdr, 0, byteArrayOf(0x11, 0x00)))
        encoded[encoded.lastIndex - 1] = (encoded[encoded.lastIndex - 1] + 1).toByte()

        assertEquals(MdrDecodeResult.BadChecksum, MdrFrameCodec.decode(encoded))
    }

    @Test
    fun `a packet that has not arrived in full is incomplete rather than invalid`() {
        val encoded = MdrFrameCodec.encode(MdrFrame(MdrDataType.DataMdr, 0, byteArrayOf(0x11, 0x00)))

        for (length in 1 until encoded.size) {
            assertEquals(
                MdrDecodeResult.Incomplete,
                MdrFrameCodec.decode(encoded.copyOfRange(0, length)),
                "a $length byte prefix should read as incomplete",
            )
        }
    }

    @Test
    fun `a length field that disagrees with the bytes present is rejected`() {
        // The real packet, with the declared length raised by one.
        val encoded = hex("3e0c000000000501004010613c")

        assertEquals(MdrDecodeResult.BadLength, MdrFrameCodec.decode(encoded))
    }

    @Test
    fun `an escape byte followed by anything unexpected is rejected`() {
        val encoded = hex("3e0c000000000401004010613c")
        encoded[1] = 0x3D.toByte()
        encoded[2] = 0x11.toByte()

        assertEquals(MdrDecodeResult.BadEscape, MdrFrameCodec.decode(encoded))
    }

    @Test
    fun `a type byte outside the table is rejected rather than guessed at`() {
        // Built by hand rather than patched, since MdrFrame refuses a type outside the table. Type
        // 0x7F, sequence 0, one payload byte, and a checksum over all of it.
        val encoded = hex("3e7f000000000101813c")

        assertEquals(MdrDecodeResult.BadMarker, MdrFrameCodec.decode(encoded))
    }

    @Test
    fun `a payload larger than the protocol allows is refused`() {
        val oversized = MdrFrame(MdrDataType.DataMdr, 0, ByteArray(MdrFrameCodec.MAX_PAYLOAD_BYTES + 1))

        val failure = runCatching { MdrFrameCodec.encode(oversized) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException, "was $failure")
    }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
