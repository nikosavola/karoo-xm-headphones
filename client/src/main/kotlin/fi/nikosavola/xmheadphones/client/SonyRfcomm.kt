package fi.nikosavola.xmheadphones.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Sony's classic control channel, which is where a headset actually answers.
 *
 * A headset runs the protocol over a serial profile it registers with SDP. It is not the
 * Tandem-over-BLE service the Sound Connect UUIDs describe: a WF-1000XM5 has no Tandem service at
 * all and answers here, and every reference implementation uses this channel.
 *
 * Both UUIDs below are Sony's own, recorded by several independent projects that talk to these
 * headsets. The XM5 generation and newer register the first and the XM4 generation and older the
 * second, and a headset registers one of them rather than both.
 */
public object RfcommTransport : HeadsetTransportFactory {
    /** What the XM5 generation and newer register. */
    public val SERVICE_V2: UUID = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2")

    /** What the XM4 generation and older register. */
    public val SERVICE_V1: UUID = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")

    /** Tried in order, newest first, since only one of them is there. */
    public val SERVICES: List<UUID> = listOf(SERVICE_V2, SERVICE_V1)

    /**
     * What one service gets before the next is tried. A headset that is on answers in well under a
     * second; this is for the one that is not, and a caller waiting on a link has to allow for
     * [SERVICES].size of these, which [worstCaseConnectMillis] spells out rather than leaving a
     * caller to multiply it out and get it wrong.
     */
    public const val CONNECT_TIMEOUT_MS: Long = 8_000L

    /** The longest a single connect can take, both services one after the other. */
    public val worstCaseConnectMillis: Long
        get() = CONNECT_TIMEOUT_MS * SERVICES.size

    override suspend fun open(device: BluetoothDevice): HeadsetTransport {
        var failure: IOException? = null
        for (service in SERVICES) {
            val socket =
                try {
                    device.createRfcommSocketToServiceRecord(service)
                } catch (refused: IOException) {
                    failure = refused
                    continue
                }
            try {
                connect(socket, device)
                return SocketTransport(socket)
            } catch (refused: IOException) {
                failure = refused
                runCatching { socket.close() }
            }
        }
        throw failure ?: IOException("the headset registers no control service")
    }

    private suspend fun connect(socket: BluetoothSocket, device: BluetoothDevice) =
        connectWithTimeout(socket, device.address)

    @SuppressLint("MissingPermission") // the client checks the permission before asking
    override fun looksReachable(device: BluetoothDevice): Boolean =
        runCatching { device.uuids.orEmpty().any { it.uuid in SERVICES } }.getOrDefault(false)

    /**
     * The profile states are asked of a context, which a platform singleton has to be handed.
     *
     * Unattached, this answers true, so the client still tries the connection it would otherwise
     * skip. Only the client that owns the default uses it, and one that supplies its own transport
     * never comes near this.
     */
    private var profiles: BluetoothManager? = null

    internal fun attach(context: Context) {
        profiles = context.applicationContext.getSystemService(BluetoothManager::class.java)
    }

    @SuppressLint("MissingPermission") // the client checks the permission before asking
    override fun carryingAudio(device: BluetoothDevice): Boolean {
        val manager = profiles ?: return true
        // Asked one profile at a time: a platform without a profile throws for it, and the Karoo in
        // hand runs no headset profile at all. One that will not answer is left out rather than
        // taking the rest of the question with it.
        val states =
            AUDIO_PROFILES.mapNotNull { profile ->
                runCatching { manager.getConnectionState(device, profile) }
                    .onFailure { Timber.d("No ${profile} state for ${device.address}: ${it.message}") }
                    .getOrNull()
            }
        if (states.isNotEmpty()) return states.any { it == BluetoothProfile.STATE_CONNECTED }
        // A platform that will not answer per device still says whether the profile is up at all.
        // The Karoo in hand does exactly that: both profiles report "profile not supported" for a
        // device, while the adapter-wide state is there. With one headset that is the same question.
        val profileStates =
            AUDIO_PROFILES.mapNotNull { profile ->
                runCatching { manager.adapter?.getProfileConnectionState(profile) }.getOrNull()
            }
        // Nothing to go on is not the same as knowing the answer, so the attempt is made.
        if (profileStates.isEmpty()) return true
        return profileStates.any { it == BluetoothProfile.STATE_CONNECTED }
    }

    private val AUDIO_PROFILES = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    /** An open socket, as the pipe the session reads and writes. */
    private class SocketTransport(private val socket: BluetoothSocket) : HeadsetTransport {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream

        override fun close() {
            runCatching { socket.close() }
        }
    }
}

/**
 * Connects [socket], or gives up on a headset that never answers.
 *
 * `connect` blocks in a native call that no interrupt stops, so it runs on a thread of its own and
 * the socket is closed under it to end the wait. That is why cancelling this closes the socket rather
 * than only dropping the wait: the thread would otherwise sit there until the stack's own timeout, on
 * a socket nothing is going to use.
 *
 * The thread is a daemon because that wait is the stack's, not this client's, and a parked thread
 * should not be able to hold the process open behind the rider's back.
 *
 * [blockingConnect] is what runs on that thread, and is the call itself unless a test wants to hand
 * it one that fails a different way. What it throws is handed back to the caller whichever kind it
 * is: a connect also throws `SecurityException` when the permission is revoked between the check and
 * the call, and an exception that escapes this thread kills the process rather than the link. Android
 * resets the permissions of an app it has not seen used for a while, which is a thing a bike computer
 * manages between rides.
 */
internal suspend fun connectWithTimeout(
    socket: BluetoothSocket,
    address: String,
    blockingConnect: () -> Unit = { socket.connect() },
) {
    val connected =
        withTimeoutOrNull(RfcommTransport.CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                thread(name = "sony-rfcomm-connect", isDaemon = true) {
                    try {
                        blockingConnect()
                        continuation.resume(Unit)
                    } catch (failure: Exception) {
                        continuation.resumeWithException(failure)
                    }
                }
            }
        }
    if (connected == null) throw IOException("timed out connecting to $address")
}
