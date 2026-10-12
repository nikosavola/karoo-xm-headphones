package fi.nikosavola.xmheadphones.client

import android.app.Application
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.karooext.testing.AwaitTimeoutException
import fi.nikosavola.karooext.testing.awaitValue
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolVersion
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real client against a headset that answers in the protocol's own bytes.
 *
 * Robolectric runs the actual BluetoothGatt code path, so what is being checked is the client's
 * scanning, its frame handling and its session, not a stubbed version of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XmHeadphonesClientTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val ble = FakeBle(app)

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
    }

    @After
    fun tearDown() {
        client.stop()
        XmHeadphonesClient.resetForTests()
    }

    @Test
    fun `connecting performs the handshake and reports what the headset is`() {
        ble.grantPermissions()
        headset = headsetWith(version = ProtocolVersion.V1)
        headset.install()

        val state = connect()

        assertEquals(HeadphonesLink.Connected, state.link)
        assertEquals(ProtocolVersion.V1, state.capabilities?.version)
        assertEquals(FakeHeadset.MODEL_NAME, state.device.modelName)
        assertEquals(FakeHeadset.FIRMWARE, state.device.firmwareVersion)
        assertEquals(80, state.batteryPercent)
        assertEquals(NoiseCancelling.Cancelling, state.noiseCancelling)
    }

    @Test
    fun `the client acknowledges every frame the headset sends`() {
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()

        connect()

        // The headset counts its own sequence 0, 1, 0, ...; the acknowledgement carries the other
        // value, so both are present once it has answered more than one request.
        val acknowledged = headset.acknowledgedSequences()
        assertTrue("acknowledged=$acknowledged", acknowledged.contains(0))
        assertTrue("acknowledged=$acknowledged", acknowledged.contains(1))
    }

    @Test
    fun `setting noise cancelling writes the V1 parameter the headset expects`() {
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()
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
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()
        connect()

        command { client.setNoiseCancelling(NoiseCancelling.Off) }

        assertArrayEquals(
            // The effect goes off while the ambient slot is left where the rider put it, which is
            // what the button on the headset does.
            hex("6802000102010000"),
            await { headset.lastNoiseControlWrite() },
        )
    }

    @Test
    fun `the headset's own report of a change reaches the state`() {
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()
        connect()

        headset.pushNoiseCancelling(NoiseCancelling.Off)

        assertEquals(NoiseCancelling.Off, await { client.state.value.noiseCancelling.takeIf { it == NoiseCancelling.Off } })
    }

    @Test
    fun `switching noise cancelling off on a V2 headset keeps the ambient level`() {
        ble.grantPermissions()
        headset = headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_FUNCTIONS)
        headset.noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(14), false)
        headset.install()
        connect()

        command { client.setNoiseCancelling(NoiseCancelling.Off) }

        val write = await { headset.lastNoiseControlWrite() }
        assertEquals("the effect is sent as off", 0x00, write[3].toInt() and 0xFF)
        assertEquals("the level is left where the rider had it", 14, write[6].toInt() and 0xFF)
    }

    @Test
    fun `a V1 headset's equalizer preset is read from the slot V1 keeps it in`() {
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()

        val state = connect()

        // The reply puts the preset in slot 0x01, which on V2 is the Clear Bass level.
        assertEquals(EqualizerPreset.Pop, state.equalizerPreset)
    }

    @Test
    fun `a V2 headset's equalizer preset is read from the slot V2 keeps it in`() {
        ble.grantPermissions()
        headset = headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_FUNCTIONS)
        headset.install()

        val state = connect()

        assertEquals(EqualizerPreset.Pop, state.equalizerPreset)
    }

    @Test
    fun `a notification arriving while a command is in flight does not stop it`() {
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()
        connect()
        // Every set now makes the headset report a change of its own before it answers.
        headset.interruptSets = true

        val accepted =
            command {
                client.setNoiseCancelling(
                    NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(6)!!, false),
                )
            }

        assertTrue("the command went unanswered", accepted)
        // The interleaved notification is applied too, and the command's own answer is what it ends on.
        await {
            client.state.value.noiseCancelling.takeIf {
                it is NoiseCancelling.Ambient && it.level.steps == 6
            }
        }
    }

    @Test
    fun `a headset that does not advertise noise cancelling is not offered it`() {
        ble.grantPermissions()
        headset = headsetWith(functions = FakeHeadset.V1_FUNCTIONS - 0x62)
        headset.install()
        val state = connect()

        assertFalse(state.supportsNoiseCancelling)
        assertNull(state.noiseCancelling)

        assertFalse(command { client.setNoiseCancelling(NoiseCancelling.Cancelling) })
        assertTrue("something was written", headset.commands().none { it == FakeHeadset.NC_SET })
    }

    @Test
    fun `a V2 headset is driven with the V2 battery and noise layouts`() {
        ble.grantPermissions()
        headset =
            headsetWith(version = ProtocolVersion.V2, functions = FakeHeadset.V2_FUNCTIONS)
        headset.install()

        val state = connect()

        assertEquals(ProtocolVersion.V2, state.capabilities?.version)
        assertEquals(80, state.batteryPercent)
        // The V2 battery question, not the V1 one.
        assertTrue(headset.commands().contains(FakeHeadset.BATTERY_GET_V2))
        assertFalse(headset.commands().contains(FakeHeadset.BATTERY_GET_V1))

        command { client.setNoiseCancelling(NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(7)!!, true)) }

        assertArrayEquals(hex("68170101010107"), await { headset.lastNoiseControlWrite() })
    }

    @Test
    fun `a V2 headset with table one switched off is reported rather than left silent`() {
        ble.grantPermissions()
        headset =
            FakeHeadset(peripheral("AA:BB:CC:DD:EE:01"), ProtocolVersion.V2, FakeHeadset.V2_FUNCTIONS, table1Enabled = false)
                .also { this.peripheral = it.peripheral }
        headset.install()
        client.start()
        ble.awaitScan()
        headset.peripheral.advertise()
        headset.peripheral.connect()
        headset.peripheral.awaitSubscribed(SonyGatt.NOTIFY)

        val error = await { client.state.value.lastError }

        assertTrue("error was: $error", error.contains("table 1 disabled"))
    }

    @Test
    fun `without the permission nothing is scanned`() {
        // setUp deliberately grants nothing.
        headset = headsetWith()
        headset.install()
        client.start()

        assertEquals(HeadphonesLink.NoPermission, await { client.state.value.link.takeIf { it == HeadphonesLink.NoPermission } })
        assertFalse(ble.isScanning)
    }

    @Test
    fun `bluetooth switched off is reported rather than searched through`() {
        ble.grantPermissions()
        ble.setEnabled(false)
        headset = headsetWith()
        headset.install()
        client.start()

        assertEquals(
            HeadphonesLink.BluetoothOff,
            await { client.state.value.link.takeIf { it == HeadphonesLink.BluetoothOff } },
        )
        assertFalse(ble.isScanning)
    }

    @Test
    fun `a command with no connection is refused rather than thrown`() {
        assertFalse(command { client.setNoiseCancelling(NoiseCancelling.Cancelling) })
    }

    @Test
    fun `the headset is remembered and looked for by address afterwards`() {
        ble.grantPermissions()
        headset = headsetWith(address = "AA:BB:CC:DD:EE:07")
        headset.install()
        connect()

        assertEquals("AA:BB:CC:DD:EE:07", client.savedAddress)

        client.forget()
        assertNull(client.savedAddress)
    }

    @Test
    fun `forgetting the headset reconnects, and commands still reach the new session`() {
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()
        connect()

        client.forget()

        // The cancelled session clears the session field as it unwinds, so the next one has to wait
        // for it: otherwise the stale cleanup can null the new session and every command after a
        // forget silently goes nowhere.
        headset.peripheral.advertise(untilMatched = true)
        headset.peripheral.connect()
        await {
            client.state.value.takeIf { it.link == HeadphonesLink.Connected && it.battery != null }
        }

        assertTrue(command { client.setNoiseCancelling(NoiseCancelling.Off) })
    }

    @Test
    fun `a dropped link is picked up again`() {
        ble.grantPermissions()
        headset = headsetWith()
        headset.install()
        connect()

        headset.disconnect()
        await { client.state.value.link.takeIf { it == HeadphonesLink.Searching } }

        headset.peripheral.advertise(untilMatched = true)
        headset.peripheral.connect()
        val reconnected =
            await(describe = {
                "no reconnect after ${headset.peripheral.connectionAttempts} attempts: " +
                    "state=${client.state.value}"
            }) {
                client.state.value.takeIf {
                    it.link == HeadphonesLink.Connected && it.battery != null
                }
            }

        assertEquals(80, reconnected.batteryPercent)
    }

    // region helpers

    private fun headsetWith(
        address: String = "AA:BB:CC:DD:EE:01",
        version: ProtocolVersion = ProtocolVersion.V1,
        functions: List<Int> = FakeHeadset.V1_FUNCTIONS,
    ): FakeHeadset {
        val peripheral = peripheral(address)
        return FakeHeadset(peripheral, version, functions).also { this.peripheral = peripheral }
    }

    private var peripheral: FakeBlePeripheral? = null

    private fun peripheral(address: String): FakeBlePeripheral =
        ble.peripheral(address, name = "WH-1000XM3") {
            service(SonyGatt.SERVICE, advertised = true) {
                characteristic(SonyGatt.WRITE, write = true, writeNoResponse = true)
                characteristic(SonyGatt.NOTIFY, notify = true)
            }
        }

    /** Starts the client, finds the headset and waits for the handshake to settle. */
    private fun connect(): XmHeadphonesState {
        client.start()
        ble.awaitScan()
        val peripheral = checkNotNull(this.peripheral)
        peripheral.advertise()
        peripheral.connect()
        await(describe = { "never subscribed: state=${client.state.value}, ${peripheral.describe()}" }) {
            true.takeIf { SonyGatt.NOTIFY in peripheral.subscribedCharacteristics }
        }
        return await(describe = {
            "never handshook: state=${client.state.value}\n" +
                "commands=${headset.commands()}\n" +
                "logs=" + logs.takeLast(20).joinToString(" | ")
        }) {
            client.state.value.takeIf { it.link == HeadphonesLink.Connected && it.battery != null }
        }
    }

    /**
     * Runs a suspend command off the test thread and waits for it, since the acknowledgement it is
     * waiting for only arrives while the main looper is being pumped, which this thread cannot do
     * while it is blocked.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun <T : Any> command(body: suspend () -> T): T {
        val pending = CoroutineScope(Dispatchers.Default).async { body() }
        return await { pending.takeIf { it.isCompleted }?.getCompleted() }
    }

    /**
     * Waits for [probe] with the main looper pumping.
     *
     * A bare timeout says only that something never happened, which is the least useful thing to know
     * about a client talking to a simulated headset, so [describe] reports what it was doing instead.
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
