package fi.nikosavola.xmheadphones.protocol

import java.io.ByteArrayOutputStream

/**
 * Rebuilds frames from a byte stream.
 *
 * A read from a socket is not a message boundary: one frame can arrive across several reads and one
 * read can carry several frames, so the bytes have to be collected and split on the markers. That is
 * safe to do naively because the encoder escapes them: no `0x3E` or `0x3C` ever appears inside a
 * frame body, so every such byte in the stream is a real marker.
 */
public class MdrFrameAssembler(
    // Derived, not guessed: a hard-coded bound below the worst case drops a legal frame mid-arrival.
    public val maxBufferedBytes: Int = MdrFrameCodec.MAX_FRAME_BYTES,
) {
    private val buffered = ByteArrayOutputStream()

    /** Appends [bytes] and returns every frame that is now complete. */
    public fun feed(bytes: ByteArray): List<MdrFrame> {
        buffered.write(bytes, 0, bytes.size)
        val found = mutableListOf<MdrFrame>()
        while (true) {
            val candidate = buffered.toByteArray()
            val start = candidate.indexOfByte(MdrFrameCodec.START_MARKER.toByte())
            if (start < 0) {
                // Nothing usable in here. Keeping nothing stops a stalled stream growing without bound.
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

    /** Drops anything held for a frame that never finished arriving. */
    public fun reset() {
        buffered.reset()
    }

    private fun ByteArray.indexOfByte(value: Byte, from: Int = 0): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }
}
