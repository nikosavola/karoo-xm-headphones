package fi.nikosavola.xmheadphones.client

import fi.nikosavola.xmheadphones.protocol.MdrDataType
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameCodec
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A GATT notification is not a message boundary, so the assembler has to cope with a frame split
 * across several of them and with several frames in one.
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

        // A notification boundary that lands in the middle of an unrelated frame's body.
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
    fun `a corrupt frame is dropped and the next one is still read`() {
        val assembler = MdrFrameAssembler()
        val corrupt = a.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }

        // The bad one never comes out; resynchronising on the next start marker recovers the good one
        // behind it rather than throwing away everything that followed.
        assertEquals(listOf(ack()), assembler.feed(corrupt + b))
    }

    private fun frame(sequence: Int, vararg payload: Int) =
        MdrFrame(MdrDataType.DataMdr, sequence, payload.map(Int::toByte).toByteArray())

    private fun ack() = MdrFrame(MdrDataType.Ack, 1, ByteArray(0))
}
