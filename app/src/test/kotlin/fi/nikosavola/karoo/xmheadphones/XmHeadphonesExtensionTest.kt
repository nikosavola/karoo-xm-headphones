package fi.nikosavola.karoo.xmheadphones

import fi.nikosavola.karooext.testing.ViewRecorder
import fi.nikosavola.karooext.testing.ViewUpdate
import fi.nikosavola.karooext.testing.inflate
import fi.nikosavola.karooext.testing.texts
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.HeadsetTransport
import fi.nikosavola.xmheadphones.client.HeadsetTransportFactory
import fi.nikosavola.xmheadphones.client.RfcommTransport
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.MdrDataType
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameAssembler
import fi.nikosavola.xmheadphones.protocol.MdrFrameCodec
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.ParcelUuid
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.xmlpull.v1.XmlPullParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds
import org.robolectric.annotation.Config

/**
 * The extension against a simulated Karoo and a simulated headset.
 *
 * The headset here is deliberately smaller than the one the client's own suite uses. It answers only
 * what the extension asks for, and the protocol itself is covered there.
 */
@RunWith(RobolectricTestRunner::class)
// The internal package is instrumented so the view emitter's throttle sees Robolectric's clock.
// It drops an update that lands within 900 ms of the last one, which would otherwise catch
// every test here.
@Config(sdk = [34], instrumentedPackages = ["io.hammerhead.karooext.internal"])
class XmHeadphonesExtensionTest {
    @get:Rule val karoo = FakeKarooRule(requireReleasedRadios = true)

    private lateinit var headset: Headset
    private lateinit var client: XmHeadphonesClient

    /** The noise cancelling field's frames, kept here because only its two tests look at them. */
    private lateinit var viewRecorder: ViewRecorder

    @Before
    fun setUp() {
        val adapter =
            checkNotNull(karoo.app.getSystemService(BluetoothManager::class.java)?.adapter)
        shadowOf(adapter).setEnabled(true)
        // The headset has to be paired for its control channel to exist at all, so this stands in for
        // the rider's own pairing step in Karoo's Bluetooth settings.
        val device = adapter.getRemoteDevice(HEADSET_ADDRESS)
        shadowOf(device).setName("WH-1000XM3")
        shadowOf(device).setUuids(arrayOf(ParcelUuid(RfcommTransport.SERVICE_V2)))
        shadowOf(adapter).setBondedDevices(setOf(device))

        headset = Headset()
        client = XmHeadphonesClient.get(karoo.app).also { it.transport = headset }
        shadowOf(karoo.app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
    }

    @After
    fun tearDown() = XmHeadphonesClient.resetForTests()

    @Test
    fun `the manifest points the Karoo system at a service that exists`() {
        // The fake host builds the extension class directly, so nothing else here would notice a
        // manifest naming a class the app does not have. That is what a rider would meet first.
        val services =
            karoo.app.packageManager.queryIntentServices(
                Intent(KAROO_EXTENSION_ACTION).setPackage(karoo.app.packageName),
                0,
            )

        assertEquals(1, services.size)
        val name = services.first().serviceInfo.name
        assertEquals(XmHeadphonesExtension::class.java.name, name)
        // Loadable, which is all the system needs to bind it and all a typo would break.
        assertNotNull(Class.forName(name))
    }

    @Test
    fun `nothing is looked for until a profile shows a headphone field`() {
        val host = karoo.host<XmHeadphonesExtension>()
        val battery = host.startStream(FieldIds.BATTERY)

        battery.await(10_000) { it is StreamState.NotAvailable }

        assertEquals(0, headset.openAttempts.get())
    }

    @Test
    fun `activating a profile with a headphone field starts looking for the headset`() {
        val host = karoo.host<XmHeadphonesExtension>()
        val battery = host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        karoo.awaitConsumer(ActiveRideProfile.Params)
        awaitSearch()

        battery.await(10_000) { it is StreamState.Searching }
        assertTrue("the extension never asked for the headset", headset.openAttempts.get() > 0)
    }

    @Test
    fun `the battery field streams what the headset reports`() {
        headset.batteryPercent = 64
        val host = karoo.host<XmHeadphonesExtension>()
        val battery = host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        connected()

        battery.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 64.0 }
    }

    @Test
    fun `the noise cancelling field shows the mode as text`() {
        headset.noiseCancelling = NoiseCancelling.Cancelling
        val host = karoo.host<XmHeadphonesExtension>()
        viewRecorder = host.startView(FieldIds.NOISE_CANCELLING, viewConfig())

        karoo.system.setActiveRideProfile(profile(FieldIds.NOISE_CANCELLING))
        connected()

        awaitFrameText(FieldIds.NOISE_CANCELLING, karoo.app.getString(R.string.nc_noise_cancelling))
    }

    @Test
    fun `the noise cancelling field says so when the headset changes mode`() {
        headset.noiseCancelling = NoiseCancelling.Cancelling
        val host = karoo.host<XmHeadphonesExtension>()
        viewRecorder = host.startView(FieldIds.NOISE_CANCELLING, viewConfig())

        karoo.system.setActiveRideProfile(profile(FieldIds.NOISE_CANCELLING))
        connected()
        awaitFrameText(FieldIds.NOISE_CANCELLING, karoo.app.getString(R.string.nc_noise_cancelling))

        // Past the view emitter's throttle window.
        RobolectricPump.advanceBy(1.seconds)
        headset.push(NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(9)!!, focusOnVoice = true))

        awaitFrameText(FieldIds.NOISE_CANCELLING, karoo.app.getString(R.string.nc_ambient_voice))
    }

    @Test
    fun `the ambient level field reports the level the rider set`() {
        headset.noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(14)!!, false)
        val host = karoo.host<XmHeadphonesExtension>()
        val ambient = host.startStream(FieldIds.AMBIENT_LEVEL)

        karoo.system.setActiveRideProfile(profile(FieldIds.AMBIENT_LEVEL))
        connected()

        ambient.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 14.0 }
    }

    @Test
    fun `the controller action turns noise cancelling off with the slot the headset reports`() {
        headset.noiseCancelling = NoiseCancelling.Cancelling
        val host = karoo.host<XmHeadphonesExtension>()
        host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        connected()

        host.bonusAction(XmHeadphonesExtension.ACTION_TOGGLE_NOISE_CANCELLING)

        karoo.awaitValue { true.takeIf { headset.noiseCancelling is NoiseCancelling.Off } }
    }

    @Test
    fun `the controller action turns noise cancelling back on`() {
        headset.noiseCancelling = NoiseCancelling.Off
        val host = karoo.host<XmHeadphonesExtension>()
        host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        connected()

        host.bonusAction(XmHeadphonesExtension.ACTION_TOGGLE_NOISE_CANCELLING)

        karoo.awaitValue {
            true.takeIf { headset.noiseCancelling is NoiseCancelling.Cancelling }
        }
    }

    @Test
    fun `the controller action connects on its own when no field is on the profile`() {
        headset.noiseCancelling = NoiseCancelling.Cancelling
        val host = karoo.host<XmHeadphonesExtension>()

        // No profile shows a headphone field, so nothing has asked for a connection and the action
        // must borrow one. It is the first thing a rider will try. The state it reads before the
        // connection starts says Idle, not "still searching".
        host.bonusAction(XmHeadphonesExtension.ACTION_TOGGLE_NOISE_CANCELLING)

        karoo.awaitValue { true.takeIf { headset.noiseCancelling is NoiseCancelling.Off } }
    }

    @Test
    fun `the ambient level reads as unavailable, not searching, when the headset never reports one`() {
        // A headset in noise cancelling mode reports a level only while in ambient mode, so nothing
        // is coming and the field must not search for the whole ride.
        headset.noiseCancelling = NoiseCancelling.Cancelling
        val host = karoo.host<XmHeadphonesExtension>()
        val ambient = host.startStream(FieldIds.AMBIENT_LEVEL)

        karoo.system.setActiveRideProfile(profile(FieldIds.AMBIENT_LEVEL))
        connected()

        // The final state, not the ones passed through. A field that cannot tell "no answer yet"
        // from "no answer ever" sits on Searching here for the rest of the ride.
        karoo.awaitValue { true.takeIf { ambient.items.lastOrNull() is StreamState.NotAvailable } }
    }

    @Test
    fun `the level the rider had stands in while noise cancelling is on`() {
        headset.noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(9)!!, false)
        val host = karoo.host<XmHeadphonesExtension>()
        val ambient = host.startStream(FieldIds.AMBIENT_LEVEL)

        karoo.system.setActiveRideProfile(profile(FieldIds.AMBIENT_LEVEL))
        connected()
        ambient.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 9.0 }

        headset.push(NoiseCancelling.Cancelling)
        val mark = ambient.mark()
        settle()

        // The level has not changed, so the field has nothing new to say. It must not start
        // searching for a level a cancelling headset never sends. The slider comes back to 9 when
        // ambient sound is turned on again, so 9 is what it shows meanwhile.
        assertTrue(
            "the field started searching: ${ambient.items.drop(mark)}",
            ambient.items.drop(mark).none { it is StreamState.Searching },
        )
        val last = ambient.items.last()
        assertTrue(
            "the field settled on $last",
            last is StreamState.Streaming && last.dataPoint.singleValue == 9.0,
        )
    }

    /**
     * The borrow stops the client when it gives up, which cancels a connect still in flight. Waiting
     * less than a connect can take would therefore kill the very attempt the press started, and every
     * press on a slow headset would fail while the same wait from a ride profile worked.
     */
    @Test
    fun `a borrowed press waits longer than a connect can take`() {
        assertTrue(
            "a press waits ${XmHeadphonesExtension.BORROW_TIMEOUT_MS} ms and a connect can take " +
                "${RfcommTransport.worstCaseConnectMillis} ms",
            XmHeadphonesExtension.BORROW_TIMEOUT_MS > RfcommTransport.worstCaseConnectMillis,
        )
    }

    @Test
    fun `a link of idle is not one a borrow can act on`() {
        // The action reads the state right after starting the connection, and idle is what that
        // state is before the connection has been asked for. Waiting for it would end the action
        // at once.
        assertFalse(HeadphonesLink.Idle.isSettled())
        assertFalse(HeadphonesLink.Searching.isSettled())
        assertFalse(HeadphonesLink.Connecting.isSettled())

        assertTrue(HeadphonesLink.Connected.isSettled())
        assertTrue(HeadphonesLink.NoPermission.isSettled())
        assertTrue(HeadphonesLink.BluetoothOff.isSettled())
        // Nothing a borrow can do either, and nothing it should hold a radio open waiting for.
        assertTrue(HeadphonesLink.NoPairedHeadset.isSettled())
    }

    @Test
    fun `the extension info the manifest points at agrees with the code`() {
        // Nothing else couples res/xml/extension_info.xml to the constants the code uses. A mismatch
        // registers a field Karoo draws with no data behind it.
        val parser = karoo.app.resources.getXml(R.xml.extension_info)
        val typeIds = mutableListOf<String>()
        val actionIds = mutableListOf<String>()
        var extensionId: String? = null
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "ExtensionInfo" -> extensionId = parser.getAttributeValue(null, "id")
                    "DataType" ->
                        parser.getAttributeValue(null, "typeId")?.let { typeIds += it }
                    "BonusAction" ->
                        parser.getAttributeValue(null, "actionId")?.let { actionIds += it }
                    else -> Unit
                }
            }
            event = parser.next()
        }

        assertEquals(XmHeadphonesExtension.EXTENSION_ID, extensionId)
        assertEquals(
            listOf(FieldIds.BATTERY, FieldIds.NOISE_CANCELLING, FieldIds.AMBIENT_LEVEL).sorted(),
            typeIds.sorted(),
        )
        assertEquals(listOf(XmHeadphonesExtension.ACTION_TOGGLE_NOISE_CANCELLING), actionIds)
    }

    @Test
    fun `a headset that drops mid-ride raises an alert`() {
        val host = karoo.host<XmHeadphonesExtension>()
        host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        connected()

        headset.disconnect()

        val alert = karoo.awaitEffect<InRideAlert> { it.id == "sony-headphones-disconnected" }
        assertEquals(karoo.app.getString(R.string.alert_disconnected_title), alert.title)
    }

    @Test
    fun `destroying the service releases the bluetooth radio`() {
        val host = karoo.host<XmHeadphonesExtension>()
        host.startStream(FieldIds.BATTERY)
        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        awaitSearch()
        karoo.awaitValue { true.takeIf { karoo.system.bluetoothClaims.isNotEmpty() } }

        karoo.close()

        assertTrue(karoo.system.bluetoothClaims.isEmpty())
    }

    private fun profile(vararg typeIds: String) =
        RideProfile(
            "p",
            "Road",
            listOf(
                RideProfile.Page(
                    false,
                    typeIds.map {
                        RideProfile.Page.Element(
                            DataType.dataTypeId(XmHeadphonesExtension.EXTENSION_ID, it),
                            1 to 1,
                        )
                    },
                ),
            ),
            false,
            "cycling",
            "road",
        )

    private fun viewConfig() = ViewConfig(gridSize = 30 to 15, viewSize = 200 to 100, textSize = 14)

    /**
     * Lets the field react to the last change before the test asks what it settled on.
     *
     * A negative assertion needs a bounded wait, not something to await. The behaviour under test is
     * that nothing arrives at all, so there is no signal to wait for.
     */
    private fun settle() {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            RobolectricPump.pumpMainLooper()
            Thread.sleep(10)
        }
    }

    private companion object {
        const val KAROO_EXTENSION_ACTION = "io.hammerhead.karooext.KAROO_EXTENSION"
        const val HEADSET_ADDRESS = "AA:BB:CC:DD:EE:01"
        const val SETTLE_MS = 1_000L
    }

    /** Finds the headset and waits until the extension has a usable connection to it. */
    private fun connected() {
        karoo.awaitValue {
            client.state.value.link.takeIf { it == HeadphonesLink.Connected }
        }
    }

    /** Waits until the extension has asked for the headset, which is what opening the channel follows. */
    private fun awaitSearch() {
        karoo.awaitValue {
            client.state.value.link.takeIf {
                it == HeadphonesLink.Searching || it == HeadphonesLink.Connecting
            }
        }
    }

    /**
     * The view redraws on every link change on the way to connected. A frame is only the one the test
     * is after once its text says so.
     */
    private fun awaitFrameText(typeId: String, expected: String) =
        viewRecorder.await(10_000) { update ->
            update is ViewUpdate.Frame &&
                update.views.inflate(karoo.app).texts() == listOf(expected)
        }

    /**
     * A headset that answers only what the extension drives. It covers the handshake, its battery and
     * its noise control. The replies are hand-written protocol bytes, not output of the code under
     * test, so a mistake in that code cannot be mirrored back by its own fake.
     *
     * It is the channel the client opens, so the client reaches it the way it reaches a socket.
     */
    private class Headset : HeadsetTransportFactory {
        @Volatile var batteryPercent: Int = 80
        @Volatile var noiseCancelling: NoiseCancelling = NoiseCancelling.Cancelling

        /** How many times the extension's client has opened the channel. */
        val openAttempts = AtomicInteger()

        @Volatile private var link: Link? = null

        override suspend fun open(device: BluetoothDevice): HeadsetTransport {
            openAttempts.incrementAndGet()
            sequence = 0
            return Link().also { link = it }
        }

        fun disconnect() = link?.close()

        /** The headset changing its own mode, as the button on it would. */
        fun push(state: NoiseCancelling) {
            noiseCancelling = state
            reply(byteArrayOf(0x69) + noiseControlReport())
        }

        private var sequence = 0

        /** One open channel, with the blocking read the client's session expects of a pipe. */
        private inner class Link : HeadsetTransport {
            private val inbound = LinkedBlockingQueue<ByteArray>()
            private val assembler = MdrFrameAssembler()
            private var pending = ByteArray(0)
            private var offset = 0

            @Volatile private var closed = false

            override val input: InputStream =
                object : InputStream() {
                    override fun read(): Int {
                        val one = ByteArray(1)
                        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
                    }

                    override fun read(destination: ByteArray, start: Int, length: Int): Int {
                        while (true) {
                            if (offset < pending.size) {
                                val count = minOf(length, pending.size - offset)
                                pending.copyInto(destination, start, offset, offset + count)
                                offset += count
                                return count
                            }
                            if (closed) return -1
                            val next = inbound.poll(READ_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                            pending = next
                            offset = 0
                        }
                    }
                }

            override val output: OutputStream =
                object : OutputStream() {
                    override fun write(one: Int) = write(byteArrayOf(one.toByte()), 0, 1)

                    override fun write(source: ByteArray, start: Int, length: Int) {
                        for (frame in assembler.feed(source.copyOfRange(start, start + length))) {
                            handle(frame)
                        }
                    }

                    override fun flush() = Unit
                }

            override fun close() {
                closed = true
            }

            fun push(frame: MdrFrame) {
                if (!closed) inbound.offer(MdrFrameCodec.encode(frame))
            }
        }

        private fun handle(frame: MdrFrame) {
            if (frame.type == MdrDataType.Ack) return
            // Every frame the headset is sent is acknowledged, with the other sequence value.
            push(MdrFrame(MdrDataType.Ack, inverse(frame.sequence), ByteArray(0)))
            val payload = frame.payload
            if (payload.isEmpty()) return
            when (payload[0].toInt() and 0xFF) {
                0x00 -> reply(byteArrayOf(0x01, 0x00, 0x40, 0x10))
                0x04 -> reply(deviceInfo(payload[1]))
                0x06 -> reply(supportFunctions())
                0x10 -> reply(byteArrayOf(0x11, 0x00, batteryPercent.toByte(), 0x00))
                0x66 -> reply(byteArrayOf(0x67) + noiseControlReport())
                0x68 -> {
                    noiseCancelling = readSet(payload)
                    reply(byteArrayOf(0x69) + noiseControlReport())
                }
                else -> Unit
            }
        }

        private fun deviceInfo(question: Byte): ByteArray {
            val text =
                if (question.toInt() and 0xFF == 0x01) {
                    "WH-1000XM3".toByteArray()
                } else {
                    "4.5.2".toByteArray()
                }
            return byteArrayOf(0x05, question, text.size.toByte()) + text
        }

        /** The nineteen functions a WH-1000XM3 advertised, in the order it sent them. */
        private fun supportFunctions(): ByteArray {
            val functions =
                intArrayOf(
                    0x11, 0xC1, 0x12, 0x13, 0x14, 0x30, 0x71, 0x62, 0x81, 0x42, 0x41, 0x51, 0xA1,
                    0xE1, 0xE2, 0xD1, 0xD2, 0xF4, 0x39,
                )
            return byteArrayOf(0x07, 0x00, functions.size.toByte()) +
                functions.map(Int::toByte).toByteArray()
        }

        /** The report a V1 headset sends. Effect, ncType, ncValue, asmType, asmId, asmValue. */
        private fun noiseControlReport(): ByteArray {
            val ambient = noiseCancelling as? NoiseCancelling.Ambient
            return byteArrayOf(
                0x02,
                if (noiseCancelling is NoiseCancelling.Off) 0x00 else 0x01,
                0x02,
                if (noiseCancelling is NoiseCancelling.Cancelling) 0x02 else 0x00,
                0x01,
                if (ambient?.focusOnVoice == true) 0x01 else 0x00,
                (ambient?.level?.steps ?: 0).toByte(),
            )
        }

        private fun readSet(payload: ByteArray): NoiseCancelling =
            when {
                payload[2] == 0x00.toByte() -> NoiseCancelling.Off
                payload[4] == 0x02.toByte() -> NoiseCancelling.Cancelling
                else ->
                    NoiseCancelling.Ambient(
                        AmbientLevel.clamped(payload[7].toInt() and 0xFF),
                        focusOnVoice = payload[6] == 0x01.toByte(),
                    )
            }

        private fun reply(payload: ByteArray) {
            push(MdrFrame(MdrDataType.DataMdr, sequence.also { sequence = inverse(it) }, payload))
        }

        private fun push(frame: MdrFrame) {
            link?.push(frame)
        }

        private fun inverse(value: Int) = 1 - value.coerceIn(0, 1)

        private companion object {
            const val READ_POLL_MS = 20L
        }
    }
}
