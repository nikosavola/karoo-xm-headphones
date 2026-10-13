package fi.nikosavola.xmheadphones.client

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.os.ParcelUuid
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.AwaitTimeoutException
import fi.nikosavola.karooext.testing.awaitValue
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolTable
import fi.nikosavola.xmheadphones.protocol.ProtocolVersion
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * Drives the real client against a headset that answers in the protocol's own bytes.
 *
 * The client is the real one, and so is the Robolectric Bluetooth stack it picks the headset out of.
 * Only the channel itself is swapped, since a test cannot pair a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34])
class XmHeadphonesClientTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private lateinit var headset: FakeHeadset
    private lateinit var client: XmHeadphonesClient
    private val logs = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        Timber.plant(
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    logs += "$tag: $message"
                }
            },
        )
        client = XmHeadphonesClient.get(app)
        // What the Karoo itself runs. setUp deliberately grants nothing, so the permission test has
        // something to withhold.
        adapter().let {
            shadowOf(it).setEnabled(true)
            shadowOf(it).setBondedDevices(emptySet())
        }
    }

    @After
    fun tearDown() {
        client.stop()
        XmHeadphonesClient.resetForTests()
    }

    @Test
    fun `connecting performs the handshake and reports what the headset is`() {
        granted()
        headsetWith()

        val state = connect()

        assertEquals(HeadphonesLink.Connected, state.link)
        assertEquals(ProtocolVersion.V1, state.capabilities?.version)
        // A V1 headset answers the handshake with a version and no table flags, which is the one
        // table. Kept because the details screen shows it, and because a device that reports table 1
        // switched off is refused rather than driven blind.
        assertEquals(setOf(ProtocolTable.Table1), state.capabilities?.tables)
        assertEquals("AA:BB:CC:DD:EE:01", state.address)
        assertEquals(FakeHeadset.MODEL_NAME, state.device.modelName)
        assertEquals(FakeHeadset.FIRMWARE, state.device.firmwareVersion)
        assertEquals(80, state.batteryPercent)
        assertEquals(NoiseCancelling.Cancelling, state.noiseCancelling)
    }

    @Test
    fun `the client acknowledges every frame the headset sends`() {
        granted()
        headsetWith()

        connect()

        // The headset counts its own sequence 0, 1, 0 and so on. The acknowledgement carries the other
        // value, so both appear once more than one request has been answered.
        val acknowledged = headset.acknowledgedSequences()
        assertTrue("acknowledged=$acknowledged", acknowledged.contains(0))
        assertTrue("acknowledged=$acknowledged", acknowledged.contains(1))
    }

    @Test
    fun `setting noise cancelling writes the V1 parameter the headset expects`() {
        granted()
        headsetWith()
        connect()

        command { client.setNoiseCancelling(NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(12)!!, false)) }

        assertArrayEquals(
            // command, inquired type, effect on, level adjustment, ambient with a level, normal, 12
            hex("680201010001000c"),
            await { headset.lastNoiseControlWrite() },
        )
    }

    @Test
    fun `switching noise cancelling off uses the slot the headset itself reports`() {
        granted()
        headsetWith()
        connect()

        command { client.setNoiseCancelling(NoiseCancelling.Off) }

        assertArrayEquals(
            // The effect goes off, but the ambient slot stays where the rider put it. That is
            // what the headset's own button does.
            hex("6802000102010000"),
            await { headset.lastNoiseControlWrite() },
        )
    }

    @Test
    fun `the headset's own report of a change reaches the state`() {
        granted()
        headsetWith()
        connect()

        headset.pushNoiseCancelling(NoiseCancelling.Off)

        val off = await { client.state.value.noiseCancelling.takeIf { it == NoiseCancelling.Off } }
        assertEquals(NoiseCancelling.Off, off)
    }

    @Test
    fun `switching noise cancelling off on a V2 headset keeps the ambient level`() {
        granted()
        headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_FUNCTIONS)
        headset.noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(14), false)
        connect()

        command { client.setNoiseCancelling(NoiseCancelling.Off) }

        val write = await { headset.lastNoiseControlWrite() }
        assertEquals("the effect is sent as off", 0x00, write[3].toInt() and 0xFF)
        assertEquals("the level is left where the rider had it", 14, write[6].toInt() and 0xFF)
    }

    @Test
    fun `a noise-adaptive headset's own adaptive settings survive a mode change made here`() {
        granted()
        headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_ADAPTIVE_FUNCTIONS)
        connect()
        // Changed after the handshake read, so only a notification can carry them to the client.
        // A client that only learns them from its own poll would still have the defaults.
        headset.adaptive = byteArrayOf(0x01, 0x02)
        headset.pushNoiseCancelling(NoiseCancelling.Ambient(AmbientLevel.clamped(14), false))
        await {
            client.state.value.noiseCancelling.takeIf {
                it is NoiseCancelling.Ambient && it.level.steps == 14
            }
        }

        command { client.setNoiseCancelling(NoiseCancelling.Cancelling) }

        assertArrayEquals(
            // command, the adaptive inquired type, effect on, noise cancelling, the level the
            // headset was on, then auto-ambient on and sensitivity low as the headset reported them.
            hex("6819010100000e0102"),
            await { headset.lastNoiseControlWrite() },
        )
    }

    @Test
    fun `a V1 headset's equalizer preset is read from the slot V1 keeps it in`() {
        granted()
        headsetWith()

        val state = connect()

        // The reply puts the preset in slot 0x01. On V2 that slot is the Clear Bass level.
        assertEquals(EqualizerPreset.Pop, state.equalizerPreset)
    }

    @Test
    fun `a V2 headset's equalizer preset is read from the slot V2 keeps it in`() {
        granted()
        headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_FUNCTIONS)

        val state = connect()

        assertEquals(EqualizerPreset.Pop, state.equalizerPreset)
    }

    @Test
    fun `a notification arriving while a command is in flight does not stop it`() {
        granted()
        headsetWith()
        connect()
        // Every set now makes the headset report a mode of its own before answering it.
        val before = headset.pushedCountOf(FakeHeadset.NC_NTFY)
        headset.interruptSets = true

        val accepted =
            command {
                client.setNoiseCancelling(
                    NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(6)!!, false),
                )
            }

        assertTrue("the command went unanswered", accepted)
        // Two reports for one set, so the set really was in flight when one of them landed. Without
        // this the flag could go dead again and the test would still pass.
        assertEquals(2, headset.pushedCountOf(FakeHeadset.NC_NTFY) - before)
        // The command's own answer is what the state ends on, not the report that overtook it.
        await {
            client.state.value.noiseCancelling.takeIf {
                it is NoiseCancelling.Ambient && it.level.steps == 6
            }
        }
    }

    @Test
    fun `a headset that does not advertise noise cancelling is not offered it`() {
        granted()
        headsetWith(functions = FakeHeadset.V1_FUNCTIONS - 0x62)
        val state = connect()

        assertFalse(state.supportsNoiseCancelling)
        assertNull(state.noiseCancelling)

        assertFalse(command { client.setNoiseCancelling(NoiseCancelling.Cancelling) })
        assertTrue("something was written", headset.commands().none { it == FakeHeadset.NC_SET })
    }

    @Test
    fun `a V2 headset is driven with the V2 battery and noise layouts`() {
        granted()
        headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_FUNCTIONS)

        val state = connect()

        assertEquals(ProtocolVersion.V2, state.capabilities?.version)
        assertEquals(setOf(ProtocolTable.Table1, ProtocolTable.Table2), state.capabilities?.tables)
        assertEquals(80, state.batteryPercent)
        // The V2 battery question, not the V1 one.
        assertTrue(headset.commands().contains(FakeHeadset.BATTERY_GET_V2))
        assertFalse(headset.commands().contains(FakeHeadset.BATTERY_GET_V1))
        // This headset reports both battery indicators, and is asked the single one, which is what a
        // ride screen has room for. Pinned so the order cannot flip without the per-bud path, which
        // has never been seen on hardware, being thought about on purpose.
        assertEquals(listOf(0x22, 0x00), headset.batteryRequests().last())

        command { client.setNoiseCancelling(NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(7)!!, true)) }

        assertArrayEquals(hex("68170101010107"), await { headset.lastNoiseControlWrite() })
    }

    @Test
    fun `a V2 headset with table one switched off is reported rather than left silent`() {
        granted()
        headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_FUNCTIONS, table1Enabled = false)
        client.start()

        val error = await { client.state.value.lastError }

        assertTrue("error was: $error", error.contains("table 1 disabled"))
    }

    @Test
    fun `without the permission nothing is opened`() {
        // setUp deliberately grants nothing.
        headsetWith()
        client.start()

        val refused =
            await { client.state.value.link.takeIf { it == HeadphonesLink.NoPermission } }
        assertEquals(HeadphonesLink.NoPermission, refused)
        assertEquals(0, headset.openAttempts.get())
    }

    @Test
    fun `bluetooth switched off is reported rather than connected through`() {
        granted()
        adapter().let { shadowOf(it).setEnabled(false) }
        headsetWith()
        client.start()

        assertEquals(
            HeadphonesLink.BluetoothOff,
            await { client.state.value.link.takeIf { it == HeadphonesLink.BluetoothOff } },
        )
        assertEquals(0, headset.openAttempts.get())
    }

    @Test
    fun `with nothing paired the search says so instead of trying anything`() {
        granted()
        headsetWith()
        adapter().let { shadowOf(it).setBondedDevices(emptySet()) }

        client.start()

        // Not the same as a paired headset that has gone quiet. Pairing is the rider's own step
        // somewhere else, and it is the only thing that can change this state.
        assertEquals(
            HeadphonesLink.NoPairedHeadset,
            await { client.state.value.link.takeIf { it == HeadphonesLink.NoPairedHeadset } },
        )
        assertEquals(0, headset.openAttempts.get())
    }

    /**
     * A paired list that cannot be read is not a paired list with nothing in it.
     *
     * The permission can go away while this loop is running, and the loop reads the list underneath
     * that check. A rider told to pair a headset they have already paired has been sent after the
     * wrong thing, and it is a state the settings screen now renders in words.
     */
    @Test
    @Config(sdk = [32, 34], shadows = [UnreadablePairedDevices::class])
    fun `a paired list that cannot be read is not reported as nothing paired`() {
        granted()

        client.start()

        assertEquals(
            HeadphonesLink.Searching,
            await { client.state.value.link.takeIf { it != HeadphonesLink.Idle } },
        )
        assertTrue(
            "nothing was logged about the read: $logs",
            logs.any { it.contains("Could not read the paired devices") },
        )
    }

    /**
     * A transport that throws where it says whether it recognises a device.
     *
     * The client asks that outside the guard around a session, and the transport is a caller's own
     * on a published API. Losing the loop to it would leave the link saying whatever it last said,
     * with nothing looking again until the screen is opened afresh.
     */
    @Test
    fun `a transport that cannot tell whether a device is reachable does not end the loop`() {
        granted()
        headsetWith()
        // Two paired devices, because a sort of one never asks for a second opinion: with a single
        // candidate for Kotlin to order, the transport is never consulted at all.
        bond("AA:BB:CC:DD:EE:02")
        client.transport =
            object : HeadsetTransportFactory {
                override suspend fun open(device: BluetoothDevice): HeadsetTransport =
                    throw IOException("nothing is behind this transport")

                override fun looksReachable(device: BluetoothDevice): Boolean =
                    throw IllegalStateException("a transport with no business being asked this")
            }

        client.start()

        // The error the open step leaves is what says the loop got past the throwing call rather
        // than dying on it. Connecting itself is no use here: it is on screen for as long as the
        // open takes to fail, which is nothing.
        val error =
            await(describe = { "state=${client.state.value}\nlogs=${logs.takeLast(10)}" }) {
                client.state.value.lastError?.takeIf {
                    it.contains("nothing is behind this transport")
                }
            }
        assertEquals("nothing is behind this transport", error)
        assertTrue(
            "the transport was never asked: $logs",
            logs.any { it.contains("could not tell whether") },
        )
    }

    @Test
    fun `a headset paired after the client gave up waiting is picked up`() {
        granted()
        headsetWith()
        adapter().let { shadowOf(it).setBondedDevices(emptySet()) }
        client.start()
        await { client.state.value.link.takeIf { it == HeadphonesLink.NoPairedHeadset } }

        // The poll that would notice a new pairing takes ten seconds, which is longer than this test
        // has. Forgetting asks for the same thing and restarts the loop at once.
        bond("AA:BB:CC:DD:EE:01")
        client.forget()

        val state = await { client.state.value.takeIf { it.link == HeadphonesLink.Connected } }
        assertEquals("AA:BB:CC:DD:EE:01", state.address)
    }

    @Test
    fun `a command with no connection is refused rather than thrown`() {
        assertFalse(command { client.setNoiseCancelling(NoiseCancelling.Cancelling) })
    }

    @Test
    fun `the headset is remembered and looked for by address afterwards`() {
        granted()
        headsetWith(address = "AA:BB:CC:DD:EE:07")
        connect()

        assertEquals("AA:BB:CC:DD:EE:07", client.savedAddress)
        assertEquals(listOf("AA:BB:CC:DD:EE:07"), headset.openedAddresses)

        client.forget()
        assertNull(client.savedAddress)
    }

    @Test
    fun `forgetting the headset reconnects, and commands still reach the new session`() {
        granted()
        headsetWith()
        connect()

        client.forget()

        // The cancelled session clears the session field as it unwinds, so the next session has to
        // wait for it. Otherwise the stale cleanup can null the new session, and every command
        // after a forget silently goes nowhere.
        await {
            client.state.value.takeIf { it.link == HeadphonesLink.Connected && it.battery != null }
        }

        assertTrue(command { client.setNoiseCancelling(NoiseCancelling.Off) })
    }

    @Test
    fun `a dropped link is picked up again`() {
        granted()
        headsetWith()
        connect()

        headset.disconnect()
        await { client.state.value.link.takeIf { it == HeadphonesLink.Searching } }

        val reconnected =
            await(
                describe = {
                    "no reconnect after ${headset.openAttempts.get()} attempts: " +
                        "state=${client.state.value}"
                },
            ) {
                client.state.value.takeIf {
                    it.link == HeadphonesLink.Connected && it.battery != null
                }
            }

        assertEquals(80, reconnected.batteryPercent)
    }

    /**
     * A second headset must not come up wearing the first one's name.
     *
     * The state keeps what a headset said across a drop on purpose, so a rider who loses the link
     * for a moment sees the last known battery rather than a blank. Carried to a different headset
     * that becomes a lie: one model name beside another's address.
     *
     * Reached by the headset going away rather than by forgetting it. Forgetting clears the whole
     * state, so it never had the problem. What does is the client moving on to the next paired
     * device by itself, which is what happens when a rider walks away from one headset and back
     * into range of another.
     */
    @Test
    fun `a different headset does not inherit the last one's name`() {
        granted()
        headsetWith(address = "AA:BB:CC:DD:EE:01")

        val first = connect()

        assertEquals("AA:BB:CC:DD:EE:01", first.address)
        assertEquals(FakeHeadset.MODEL_NAME, first.device.modelName)

        // A second headset is paired. It answers the handshake but not the questions about itself,
        // which is the case that would otherwise leave the first one's name on the screen for as
        // long as the link lasted.
        bond("AA:BB:CC:DD:EE:02")
        headset.answersDeviceInfo = false
        val gone = "AA:BB:CC:DD:EE:01"
        client.transport =
            HeadsetTransportFactory { device ->
                if (device.address == gone) throw IOException("the headset is gone")
                headset.open(device)
            }

        headset.disconnect()

        // A longer wait than the rest: a headset that says nothing about itself is held to the
        // handshake timeout twice over, once per question, before the session gives up on it and
        // carries on with the handshake.
        val second =
            await(
                timeoutMs = 30_000,
                describe = { "no second headset: state=${client.state.value}" },
            ) {
                client.state.value.takeIf {
                    it.link == HeadphonesLink.Connected && it.address == "AA:BB:CC:DD:EE:02"
                }
            }

        assertNull(second.device.modelName)
        assertNull(second.device.firmwareVersion)
    }

    /**
     * A failure belongs to the headset that failed.
     *
     * The first of two headsets is unreachable. Its error stays up while it is retried, since that
     * is what tells a rider why nothing is happening, and goes the moment the second one is tried:
     * beside a Connecting link it reads as the second headset's, which is the one being waited on.
     */
    @Test
    fun `a failure of one headset is not shown while another is being tried`() {
        granted()
        headsetWith(address = "AA:BB:CC:DD:EE:01")
        bond("AA:BB:CC:DD:EE:02")
        // Nothing is remembered as the last headset, so the two come out in the order they were
        // paired and the unreachable one is the one tried first.
        client.forget()
        val gone = "AA:BB:CC:DD:EE:01"
        val holding = CountDownLatch(1)
        client.transport =
            object : HeadsetTransportFactory {
                override suspend fun open(device: BluetoothDevice): HeadsetTransport {
                    if (device.address == gone) throw IOException("the headset is gone")
                    // Held open, so there is something to read the state during an attempt.
                    holding.await()
                    return headset.open(device)
                }
            }

        client.start()

        try {
            await { client.state.value.lastError?.takeIf { it.contains("the headset is gone") } }
            // Both at once: the attempt that just failed also leaves a Connecting link behind, still
            // carrying its own error, and that one is not the state this is looking for.
            await(describe = { "state=${client.state.value}" }) {
                client.state.value.takeIf {
                    it.link == HeadphonesLink.Connecting && it.lastError == null
                }
            }
        } finally {
            // Or a failed assertion leaves this attempt parked on an IO thread for the rest of the
            // run, since the wait itself cannot be interrupted.
            holding.countDown()
        }

        await { client.state.value.takeIf { it.link == HeadphonesLink.Connected } }
    }

    @Test
    fun `a paired device the transport recognises is tried before one it does not`() {
        granted()
        headsetWith(address = "AA:BB:CC:DD:EE:02", advertises = false)
        bond("AA:BB:CC:DD:EE:01", advertises = true)
        headset.answers = false
        // Which of them the transport recognises is its own business. What the client owes is to
        // ask it and to try what it names first, which is what this holds it to.
        headset.reachableAddresses = setOf("AA:BB:CC:DD:EE:01")

        client.start()

        await { headset.openAttempts.get().takeIf { it >= 1 } }

        assertEquals(
            "the device the transport recognised goes first",
            "AA:BB:CC:DD:EE:01",
            headset.openedAddresses.first(),
        )
    }

    // region helpers

    private fun adapter() =
        checkNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter)

    private fun granted() = shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)

    /** Puts a device into the paired list, as the rider's own pairing step would have. */
    private fun bond(
        address: String,
        advertises: Boolean = true,
        name: String = "WH-1000XM3",
    ): BluetoothDevice {
        val device = adapter().getRemoteDevice(address)
        shadowOf(device).setName(name)
        shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        if (advertises) shadowOf(device).setUuids(arrayOf(ParcelUuid(RfcommTransport.SERVICE_V2)))
        val adapter = adapter()
        shadowOf(adapter).setBondedDevices(adapter.bondedDevices + device)
        return device
    }

    private fun headsetWith(
        address: String = "AA:BB:CC:DD:EE:01",
        version: ProtocolVersion = ProtocolVersion.V1,
        functions: List<Int> = FakeHeadset.V1_FUNCTIONS,
        table1Enabled: Boolean = true,
        advertises: Boolean = true,
    ): FakeHeadset {
        bond(address, advertises)
        return FakeHeadset(version, functions, table1Enabled)
            .also {
                headset = it
                client.transport = it
            }
    }

    /** Starts the client and waits until it has a usable connection. */
    private fun connect(): XmHeadphonesState {
        client.start()
        return await(describe = {
            "never handshook: state=${client.state.value}\n" +
                "commands=${headset.commands()}\n" +
                "logs=" + logs.takeLast(20).joinToString(" | ")
        }) {
            client.state.value.takeIf { it.link == HeadphonesLink.Connected && it.battery != null }
        }
    }

    /**
     * Runs a suspend command off the test thread and waits for it. The acknowledgement only arrives
     * while the main looper is being pumped, which this thread cannot do while blocked.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T : Any> command(body: suspend () -> T): T {
        val pending = CoroutineScope(Dispatchers.Default).async { body() }
        return await { pending.takeIf { it.isCompleted }?.getCompleted() }
    }

    /**
     * Waits for [probe] with the main looper pumping.
     *
     * A bare timeout only says something never happened, which is the least useful thing to know
     * here. [describe] reports what the client was doing instead.
     */
    private fun <T : Any> await(
        timeoutMs: Long = 10_000,
        describe: () -> String = { "timed out waiting for the client state" },
        probe: () -> T?,
    ): T =
        try {
            awaitValue(timeoutMs, RobolectricPump::pumpMainLooper, probe)
        } catch (timeout: AwaitTimeoutException) {
            throw AssertionError(describe(), timeout)
        }

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // endregion
}

/**
 * A phone whose paired list cannot be read, which is what a permission going away mid-loop looks
 * like from the client. Everything else about the adapter is the ordinary Robolectric one.
 */
@Implements(BluetoothAdapter::class)
class UnreadablePairedDevices : ShadowBluetoothAdapter() {
    @Implementation
    override fun getBondedDevices(): Set<BluetoothDevice> =
        throw SecurityException("the permission went away")
}
