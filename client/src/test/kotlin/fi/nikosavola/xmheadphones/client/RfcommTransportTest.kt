package fi.nikosavola.xmheadphones.client

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.test.core.app.ApplicationProvider
import android.os.ParcelUuid
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The real channel, over Robolectric's own Bluetooth stack.
 *
 * Every other suite swaps the channel out so a headset can answer in the protocol's own bytes. This
 * is the one place the socket underneath is the code under test, so a mistake here would otherwise
 * only show up on a bike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34])
class RfcommTransportTest {
    private val device: BluetoothDevice =
        checkNotNull(
            ApplicationProvider.getApplicationContext<Application>()
                .getSystemService(BluetoothManager::class.java)
                ?.adapter,
        ).getRemoteDevice("AA:BB:CC:DD:EE:01")

    /**
     * Both values, pinned. A typo in either is not something a headset can report: the lookup simply
     * finds no service, which reads as a headset that is not there.
     */
    @Test
    fun `the services are Sony's own, the newest first`() {
        assertEquals(
            UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2"),
            RfcommTransport.SERVICE_V2,
        )
        assertEquals(
            UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba"),
            RfcommTransport.SERVICE_V1,
        )
        assertEquals(
            listOf(RfcommTransport.SERVICE_V2, RfcommTransport.SERVICE_V1),
            RfcommTransport.SERVICES,
        )
    }

    @Test
    fun `the channel opens and carries bytes both ways`() {
        // The socket the transport will be handed, which the shadow keeps one of per test. If a
        // later version hands out a fresh one, the bytes below go to a pipe nothing reads and the
        // waits fail, rather than the reads blocking the suite forever.
        val socket = device.createRfcommSocketToServiceRecord(RfcommTransport.SERVICE_V2)
        val channel = shadowOf(socket)

        runBlocking {
            val transport = RfcommTransport.open(device)
            try {
                channel.inputStreamFeeder.write(byteArrayOf(0x3E, 0x0C))
                channel.inputStreamFeeder.flush()
                assertTrue("the bytes never reached the channel", awaitBytes(transport.input))
                val arrived = ByteArray(2)
                assertEquals(2, transport.input.read(arrived))
                assertArrayEquals(byteArrayOf(0x3E, 0x0C), arrived)

                transport.output.write(byteArrayOf(0x01, 0x02))
                transport.output.flush()
                assertTrue("the frame never left the channel", awaitBytes(channel.outputStreamSink))
                val sent = ByteArray(2)
                assertEquals(2, channel.outputStreamSink.read(sent))
                assertArrayEquals(byteArrayOf(0x01, 0x02), sent)
            } finally {
                transport.close()
            }
        }
    }

    /**
     * A revoked permission is not an IOException. A thread that lets one escape ends the process, and
     * Android resets the permissions of an app it has not seen used for a while, which a bike computer
     * manages between rides. It has to come back as a failed connect instead.
     */
    @Test
    fun `a connect refused for a reason other than the link ends the attempt, not the process`() {
        // The shadow can only be told to refuse with an IOException, which is the case the code
        // under test already handled. The call is handed over directly so the other case is reached.
        val socket = device.createRfcommSocketToServiceRecord(RfcommTransport.SERVICE_V2)

        val failure =
            assertThrows(SecurityException::class.java) {
                runBlocking {
                    connectWithTimeout(socket, "AA:BB:CC:DD:EE:01") {
                        throw SecurityException("BLUETOOTH_CONNECT is not granted")
                    }
                }
            }

        assertEquals("BLUETOOTH_CONNECT is not granted", failure.message)
    }

    @Test
    fun `a device advertising either service looks reachable`() {
        shadowOf(device).setUuids(arrayOf(ParcelUuid(RfcommTransport.SERVICE_V2)))
        assertTrue(RfcommTransport.looksReachable(device))

        shadowOf(device).setUuids(arrayOf(ParcelUuid(RfcommTransport.SERVICE_V1)))
        assertTrue(RfcommTransport.looksReachable(device))
    }

    /**
     * A no from this only loses a device its place in the queue, so it has to be a plain no and
     * never a refusal: a device whose services were never read still gets tried.
     */
    @Test
    fun `a device with no control service does not look reachable, and says so rather than throwing`() {
        shadowOf(device).setUuids(arrayOf(ParcelUuid(UUID.randomUUID())))
        assertFalse(RfcommTransport.looksReachable(device))

        shadowOf(device).setUuids(null)
        assertFalse(RfcommTransport.looksReachable(device))
    }

    /**
     * The wait for a headset that never answers ends by closing the socket under a native call that
     * nothing interrupts. Nothing else in this suite reaches that path, because the shadow's connect
     * returns at once, and it is the one thing here that would leak a thread per attempt if it broke.
     */
    @Test
    fun `giving up on a connect closes the socket it was waiting on`() = runBlocking {
        val socket = device.createRfcommSocketToServiceRecord(RfcommTransport.SERVICE_V2)
        val channel = shadowOf(socket)
        channel.blockConnect()

        val attempt =
            CoroutineScope(Dispatchers.Default).launch {
                runCatching { RfcommTransport.open(device) }
            }
        // Long enough for the connect to park in the call it is about to be taken out of.
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        attempt.cancelAndJoin()
        channel.unblockConnect()

        // A socket that was closed under the connect refuses the next one. One left open takes it,
        // and the thread parked in it goes on waiting for a headset nothing is listening for.
        val failure = assertThrows(IOException::class.java) { socket.connect() }
        assertTrue("closed as: ${failure.message}", failure.message.orEmpty().contains("closed"))
    }

    /** Reads block, so an assertion that nothing arrived has to be a wait rather than a read. */
    private fun awaitBytes(input: InputStream, timeoutMs: Long = 2_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { input.available() }.getOrDefault(0) > 0) return true
            Thread.sleep(10)
        }
        return false
    }

    /**
     * A headset that is off, or out of range, is what a channel that will not open looks like. The
     * shadow keeps one socket per test and a closed one cannot be reopened, so closing it first is
     * how that is reached here; the message is the one set below, which is what comes back out.
     */
    @Test
    fun `a headset that will not answer ends in an IOException rather than a wait`() {
        val socket = device.createRfcommSocketToServiceRecord(RfcommTransport.SERVICE_V2)
        shadowOf(socket).setConnectException(IOException("no route to host"))
        socket.close()

        val failure =
            assertThrows(IOException::class.java) {
                runBlocking { RfcommTransport.open(device) }
            }

        assertEquals("no route to host", failure.message)
    }
}
