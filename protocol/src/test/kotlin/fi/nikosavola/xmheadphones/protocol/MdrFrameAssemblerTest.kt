package fi.nikosavola.xmheadphones.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A read from a socket is not a message boundary. A frame can arrive split across several reads, and
 * several frames can arrive in one.
 */
class MdrFrameAssemblerTest {

    private val a = MdrFrameCodec.encode(MdrFrame(MdrDataType.DataMdr, 0, byteArrayOf(0x01, 0x02)))
    private val b = MdrFrameCodec.encode(MdrFrame(MdrDataType.Ack, 1, ByteArray(0)))

    @Test
    fun `a whole frame in one notification comes out whole`() {
        val assembler = MdrFrameAssembler()

        assertEquals(listOf(frame(0, 0x01, 0x02)), assembler.feed(a))
    }

    @Test
    fun `a frame split across notifications is held until its end arrives`() {
        val assembler = MdrFrameAssembler()

        assertEquals(emptyList<MdrFrame>(), assembler.feed(a.copyOfRange(0, 3)))
        assertEquals(emptyList<MdrFrame>(), assembler.feed(a.copyOfRange(3, a.size - 1)))
        assertEquals(listOf(frame(0, 0x01, 0x02)), assembler.feed(a.copyOfRange(a.size - 1, a.size)))
    }

    @Test
    fun `two frames in one notification both come out`() {
        val assembler = MdrFrameAssembler()

        assertEquals(listOf(frame(0, 0x01, 0x02), ack()), assembler.feed(a + b))
    }

    @Test
    fun `a frame that starts mid-notification is recovered`() {
        val assembler = MdrFrameAssembler()

        // A boundary landing in the middle of an unrelated frame's body.
        assertEquals(listOf(frame(0, 0x01, 0x02)), assembler.feed(byteArrayOf(0x11, 0x22) + a))
    }

    @Test
    fun `leading noise is dropped and the next frame still arrives`() {
        val assembler = MdrFrameAssembler()

        assertEquals(emptyList<MdrFrame>(), assembler.feed(byteArrayOf(0x00, 0x7F, 0x10)))
        assertEquals(listOf(frame(0, 0x01, 0x02)), assembler.feed(a))
    }

    @Test
    fun `a start marker with no end marker does not grow without bound`() {
        val assembler = MdrFrameAssembler(maxBufferedBytes = 32)

        assertEquals(emptyList<MdrFrame>(), assembler.feed(ByteArray(64) { 0x3E }))
        // The stalled bytes were dropped, so a real frame after them is still recognised.
        assertEquals(listOf(frame(0, 0x01, 0x02)), assembler.feed(a))
    }

    @Test
    fun `the largest frame the encoder can produce survives arriving in pieces`() {
        val assembler = MdrFrameAssembler()
        // Every byte needs escaping, which is as long as a frame gets.
        val worst = MdrFrameCodec.encode(MdrFrame(MdrDataType.DataMdr, 0, ByteArray(MAX) { 0x3C }))
        assertTrue(
            "a frame longer than the bound: ${worst.size} bytes",
            worst.size <= MdrFrameCodec.MAX_FRAME_BYTES,
        )
        // And longer than the fixed bound this replaced. Without this the test would go on passing
        // if the payload cap ever dropped far enough for the two bounds to swap places.
        assertTrue("only ${worst.size} bytes, so nothing is being tested", worst.size > FIXED_BOUND)

        // Everything but the closing marker in one notification, so the buffer holds all of it while
        // still incomplete. That is the moment a bound below the frame's own length throws it away.
        val found =
            assembler.feed(worst.copyOfRange(0, worst.size - 1)) +
                assembler.feed(worst.copyOfRange(worst.size - 1, worst.size))

        assertEquals(1, found.size)
        assertEquals(MAX, found.single().payload.size)
    }

    @Test
    fun `a corrupt frame is dropped and the next one is still read`() {
        val assembler = MdrFrameAssembler()
        val corrupt = a.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }

        // The bad frame never comes out. Resynchronising on the next start marker still recovers the
        // good frame behind it, instead of discarding everything after it.
        assertEquals(listOf(ack()), assembler.feed(corrupt + b))
    }

    private fun frame(sequence: Int, vararg payload: Int) =
        MdrFrame(MdrDataType.DataMdr, sequence, payload.map(Int::toByte).toByteArray())

    private fun ack() = MdrFrame(MdrDataType.Ack, 1, ByteArray(0))

    private companion object {
        val MAX = MdrFrameCodec.MAX_PAYLOAD_BYTES

        /** The assembler's old hard-coded buffer, which was smaller than a legal frame. */
        const val FIXED_BOUND = 4096
    }
}
