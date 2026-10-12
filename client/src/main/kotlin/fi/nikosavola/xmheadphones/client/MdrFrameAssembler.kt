package fi.nikosavola.xmheadphones.client

import fi.nikosavola.xmheadphones.protocol.MdrDecodeResult
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameCodec
import java.io.ByteArrayOutputStream

/**
 * Rebuilds frames from a notification stream.
 *
 * A GATT notification is not a message boundary: one frame can arrive across several of them and one
 * can carry several frames, so the bytes have to be collected and split on the markers. That is
 * safe to do naively because the encoder escapes them: no `0x3E` or `0x3C` ever appears inside a
 * frame body, so every such byte in the stream is a real marker.
 */
internal class MdrFrameAssembler(private val maxBufferedBytes: Int = 4096) {
    private val buffered = ByteArrayOutputStream()

    /** Appends [bytes] and returns every frame that is now complete. */
    fun feed(bytes: ByteArray): List<MdrFrame> {
        buffered.write(bytes, 0, bytes.size)
        val found = mutableListOf<MdrFrame>()
        while (true) {
            val candidate = buffered.toByteArray()
            val start = candidate.indexOfByte(MdrFrameCodec.START_MARKER.toByte())
            if (start < 0) {
                // Nothing usable in here; keep nothing so a stalled stream cannot grow without bound.
                buffered.reset()
                break
            }
            if (start > 0) {
                buffered.reset()
                buffered.write(candidate, start, candidate.size - start)
                continue
            }
            val end = candidate.indexOfByte(MdrFrameCodec.END_MARKER.toByte(), from = 1)
            if (end < 0) {
                if (candidate.size > maxBufferedBytes) buffered.reset()
                break
            }
            when (val result = MdrFrameCodec.decode(candidate.copyOfRange(0, end + 1))) {
                is MdrDecodeResult.Decoded -> {
                    found += result.frame
                    buffered.reset()
                    buffered.write(candidate, end + 1, candidate.size - end - 1)
                }
                // A well-formed-looking pair of markers that is not a frame: drop the start byte and
                // resynchronise on the next one rather than discarding everything after it.
                else -> {
                    buffered.reset()
                    buffered.write(candidate, 1, candidate.size - 1)
                }
            }
        }
        return found
    }

    fun reset() = buffered.reset()

    private fun ByteArray.indexOfByte(value: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }
}
