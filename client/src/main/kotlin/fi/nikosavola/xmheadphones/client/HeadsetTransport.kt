package fi.nikosavola.xmheadphones.client

import android.bluetooth.BluetoothDevice
import java.io.InputStream
import java.io.OutputStream

/**
 * The pipe to a headset, once it is open.
 *
 * The session is written against this rather than a socket, because what carries the bytes is the
 * one part that differs between platforms and the one part a test can supply without a radio.
 */
public interface HeadsetTransport {
    /** What the headset sent. Blocking, so the session reads it from its own coroutine. */
    public val input: InputStream

    /**
     * Where frames go.
     *
     * The protocol's own acknowledgement is the receipt, so a write needs no reply and a frame
     * written is not a frame that arrived.
     */
    public val output: OutputStream

    /** Ends the pipe. A reader blocked in [input] has to come out of it. */
    public fun close()
}

/**
 * How a pipe to a headset is opened.
 *
 * [open] throws when the headset does not answer, which is the only thing left to say about it.
 * [RfcommTransport] is the classic Bluetooth one, and the default a caller gets.
 */
public fun interface HeadsetTransportFactory {
    public suspend fun open(device: BluetoothDevice): HeadsetTransport

    /**
     * Whether [device] looks like one [open] could reach, which only decides the order candidates
     * are tried in. It never rules one out: a device this cannot tell about is still tried.
     *
     * A factory with nothing to go on leaves this alone. Everything then ranks the same and the
     * devices are tried in the order the platform lists them, which is the right answer for a
     * transport that does not recognise its peer from outside.
     */
    public fun looksReachable(device: BluetoothDevice): Boolean = true

    /**
     * Whether [device] is carrying audio at this moment, which is the cheapest sign that it is
     * switched on and in reach: a headset the rider is listening to is connected for sound.
     *
     * A device that is not is asleep, off or out of range, and the client skips opening it for a
     * while rather than paging the radio on the chance that it answers. Null is a transport that
     * cannot tell, which is not the same answer: the connection is attempted.
     */
    public fun carriesAudio(device: BluetoothDevice): Boolean? = null
}
