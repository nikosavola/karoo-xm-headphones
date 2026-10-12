package fi.nikosavola.karoo.xmheadphones

import fi.nikosavola.karooext.testing.ViewRecorder
import fi.nikosavola.karooext.testing.ViewUpdate
import fi.nikosavola.karooext.testing.inflate
import fi.nikosavola.karooext.testing.texts
import fi.nikosavola.karooext.testing.robolectric.FakeKarooRule
import fi.nikosavola.karooext.testing.robolectric.RobolectricPump
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBle
import fi.nikosavola.karooext.testing.robolectric.ble.FakeBlePeripheral
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.MdrDataType
import fi.nikosavola.xmheadphones.protocol.MdrDecodeResult
import fi.nikosavola.xmheadphones.protocol.MdrFrame
import fi.nikosavola.xmheadphones.protocol.MdrFrameCodec
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import android.content.Intent
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
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
import kotlin.time.Duration.Companion.seconds
import org.robolectric.annotation.Config

/**
 * The extension against a simulated Karoo and a simulated headset.
 *
 * The headset here is deliberately smaller than the one the client's own suite uses: it answers only
 * what the extension asks for, and the protocol itself is covered there.
 */
@RunWith(RobolectricTestRunner::class)
// The internal package is instrumented so the view emitter's throttle sees Robolectric's clock: it
// drops an update that lands within 900 ms of the last one, which every test here would otherwise be
// caught by.
@Config(sdk = [34], instrumentedPackages = ["io.hammerhead.karooext.internal"])
class XmHeadphonesExtensionTest {
    @get:Rule val karoo = FakeKarooRule(requireReleasedRadios = true)

    private val ble = FakeBle(karoo.app)
    private lateinit var headset: Headset

    /** The noise cancelling field's frames, kept here because only its two tests look at them. */
    private lateinit var viewRecorder: ViewRecorder

    @Before
    fun setUp() {
        ble.grantPermissions()
        headset = Headset(ble)
    }

    @After
    fun tearDown() = XmHeadphonesClient.resetForTests()

    @Test
    fun `the manifest points the Karoo system at a service that exists`() {
        // The fake host builds the extension class directly, so nothing else here would notice a
        // manifest that names a class the app does not have, which is what a rider would meet first.
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

        assertFalse(ble.isScanning)
    }

    @Test
    fun `activating a profile with a headphone field starts looking for the headset`() {
        val host = karoo.host<XmHeadphonesExtension>()
        val battery = host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        karoo.awaitConsumer(ActiveRideProfile.Params)
        ble.awaitScan()

        battery.await(10_000) { it is StreamState.Searching }
        assertTrue(ble.isScanning)
    }

    @Test
    fun `the battery field streams what the headset reports`() {
        headset.batteryPercent = 64
        headset.install()
        val host = karoo.host<XmHeadphonesExtension>()
        val battery = host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        connected()

        battery.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 64.0 }
    }

    @Test
    fun `the noise cancelling field shows the mode as text`() {
        headset.noiseCancelling = NoiseCancelling.Cancelling
        headset.install()
        val host = karoo.host<XmHeadphonesExtension>()
        viewRecorder = host.startView(FieldIds.NOISE_CANCELLING, viewConfig())

        karoo.system.setActiveRideProfile(profile(FieldIds.NOISE_CANCELLING))
        connected()

        awaitFrameText(FieldIds.NOISE_CANCELLING, karoo.app.getString(R.string.nc_noise_cancelling))
    }

    @Test
    fun `the noise cancelling field says so when the headset changes mode`() {
        headset.noiseCancelling = NoiseCancelling.Cancelling
        headset.install()
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
        headset.install()
        val host = karoo.host<XmHeadphonesExtension>()
        val ambient = host.startStream(FieldIds.AMBIENT_LEVEL)

        karoo.system.setActiveRideProfile(profile(FieldIds.AMBIENT_LEVEL))
        connected()

        ambient.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 14.0 }
    }

    @Test
    fun `the controller action turns noise cancelling off with the slot the headset reports`() {
        headset.noiseCancelling = NoiseCancelling.Cancelling
        headset.install()
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
        headset.install()
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
        headset.install()
        val host = karoo.host<XmHeadphonesExtension>()

        // Nothing has asked for a connection, because no profile shows a headphone field, so the
        // action has to borrow one. It is the first thing a rider will try, and the state it reads
        // before the connection starts says Idle rather than "still searching".
        host.bonusAction(XmHeadphonesExtension.ACTION_TOGGLE_NOISE_CANCELLING)
        headset.connect()

        karoo.awaitValue { true.takeIf { headset.noiseCancelling is NoiseCancelling.Off } }
    }

    @Test
    fun `the ambient level reads as unavailable, not searching, when the headset never reports one`() {
        // A headset in noise cancelling mode reports a level only while it is in ambient mode, so
        // there is nothing coming and the field must not search for the whole ride.
        headset.noiseCancelling = NoiseCancelling.Cancelling
        headset.install()
        val host = karoo.host<XmHeadphonesExtension>()
        val ambient = host.startStream(FieldIds.AMBIENT_LEVEL)

        karoo.system.setActiveRideProfile(profile(FieldIds.AMBIENT_LEVEL))
        connected()

        // What it settles on, not what it passed through: a field that cannot tell "no answer yet"
        // from "no answer ever" sits on Searching here for the rest of the ride.
        karoo.awaitValue { true.takeIf { ambient.items.lastOrNull() is StreamState.NotAvailable } }
    }

    @Test
    fun `the level the rider had stands in while noise cancelling is on`() {
        headset.noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.fromStepsOrNull(9)!!, false)
        headset.install()
        val host = karoo.host<XmHeadphonesExtension>()
        val ambient = host.startStream(FieldIds.AMBIENT_LEVEL)

        karoo.system.setActiveRideProfile(profile(FieldIds.AMBIENT_LEVEL))
        connected()
        ambient.await(10_000) { it is StreamState.Streaming && it.dataPoint.singleValue == 9.0 }

        headset.push(NoiseCancelling.Cancelling)
        val mark = ambient.mark()
        settle()

        // The level has not changed, so the field has nothing new to say; what matters is that it does
        // not start searching for a level a headset in noise cancelling mode will not send. The slider
        // comes back to 9 when ambient sound is turned on again, so 9 is what it shows meanwhile.
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

    @Test
    fun `a link of idle is not one a borrow can act on`() {
        // The action reads the state right after starting the connection, and idle is what it says
        // before the connection has been asked for. Waiting for it would end the action at once.
        assertFalse(HeadphonesLink.Idle.isSettled())
        assertFalse(HeadphonesLink.Searching.isSettled())
        assertFalse(HeadphonesLink.Connecting.isSettled())

        assertTrue(HeadphonesLink.Connected.isSettled())
        assertTrue(HeadphonesLink.NoPermission.isSettled())
        assertTrue(HeadphonesLink.BluetoothOff.isSettled())
    }

    @Test
    fun `the extension info the manifest points at agrees with the code`() {
        // Nothing else couples res/xml/extension_info.xml to the constants the code uses, and a
        // mismatch registers a field Karoo draws with no data behind it.
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
        headset.install()
        val host = karoo.host<XmHeadphonesExtension>()
        host.startStream(FieldIds.BATTERY)

        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        connected()

        headset.peripheral.disconnect()

        val alert = karoo.awaitEffect<InRideAlert> { it.id == "sony-headphones-disconnected" }
        assertEquals(karoo.app.getString(R.string.alert_disconnected_title), alert.title)
    }

    @Test
    fun `destroying the service releases the bluetooth radio`() {
        val host = karoo.host<XmHeadphonesExtension>()
        host.startStream(FieldIds.BATTERY)
        karoo.system.setActiveRideProfile(profile(FieldIds.BATTERY))
        ble.awaitScan()
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
     * A negative assertion needs a bounded wait rather than something to await: the behaviour under
     * test is that nothing arrives at all, so there is no signal to wait for.
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
        const val SETTLE_MS = 1_000L
    }

    /** Finds the headset and waits until the extension has a usable connection to it. */
    private fun connected() {
        headset.connect()
        karoo.awaitValue {
            XmHeadphonesClient.get(karoo.app).state.value.link.takeIf {
                it == HeadphonesLink.Connected
            }
        }
    }

    /**
     * The view redraws on every link change on the way to connected, so a frame is only the one the
     * test is after once its text says so.
     */
    private fun awaitFrameText(typeId: String, expected: String) =
        viewRecorder.await(10_000) { update ->
            update is ViewUpdate.Frame &&
                update.views.inflate(karoo.app).texts() == listOf(expected)
        }

    /**
     * A headset that answers only what the extension drives: the handshake, its battery and its
     * noise control. The replies are written out as protocol bytes rather than produced by the code
     * under test, so a mistake in that code cannot be mirrored back by its own fake.
     */
    private class Headset(ble: FakeBle) {
        @Volatile var batteryPercent: Int = 80
        @Volatile var noiseCancelling: NoiseCancelling = NoiseCancelling.Cancelling

        val peripheral: FakeBlePeripheral =
            ble.peripheral("AA:BB:CC:DD:EE:01", name = "WH-1000XM3") {
                service(SERVICE, advertised = true) {
                    characteristic(WRITE, write = true, writeNoResponse = true)
                    characteristic(NOTIFY, notify = true)
                }
            }

        private var sequence = 0

        fun install() {
            peripheral.onWrite(WRITE) { bytes -> handle(bytes) }
        }

        fun connect() {
            peripheral.advertise()
            peripheral.connect()
            peripheral.awaitSubscribed(NOTIFY)
        }

        /** The headset changing its own mode, as the button on it would. */
        fun push(state: NoiseCancelling) {
            noiseCancelling = state
            reply(byteArrayOf(0x69) + noiseControlReport())
        }

        private fun handle(bytes: ByteArray) {
            val frame = decode(bytes) ?: return
            if (frame.type == MdrDataType.Ack) return
            // Every frame the headset is sent is acknowledged, with the other sequence value.
            notify(MdrFrame(MdrDataType.Ack, inverse(frame.sequence), ByteArray(0)))
            val payload = frame.payload
            if (payload.isEmpty()) return
            when (val command = payload[0].toInt() and 0xFF) {
                0x00 -> reply(byteArrayOf(0x01, 0x00, 0x40, 0x10))
                0x04 -> reply(deviceInfo(payload[1]))
                0x06 -> reply(supportFunctions())
                0x10 ->
                    reply(byteArrayOf(0x11, 0x00, batteryPercent.toByte(), 0x00))
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

        /** Effect, ncType, ncValue, asmType, asmId, asmValue: the report a V1 headset sends. */
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
            notify(MdrFrame(MdrDataType.DataMdr, sequence.also { sequence = inverse(it) }, payload))
        }

        private fun notify(frame: MdrFrame) =
            peripheral.notify(NOTIFY, MdrFrameCodec.encode(frame))

        private fun decode(bytes: ByteArray) =
            (MdrFrameCodec.decode(bytes) as? MdrDecodeResult.Decoded)?.frame

        private fun inverse(value: Int) = 1 - value.coerceIn(0, 1)

        private companion object {
            /** Sony's Tandem control channel, as the client declares it. */
            val SERVICE = java.util.UUID.fromString("5b833e20-6bc7-4802-8e9a-723ceca4bd8f")
            val WRITE = java.util.UUID.fromString("5b833c60-6bc7-4802-8e9a-723ceca4bd8f")
            val NOTIFY = java.util.UUID.fromString("5b833c61-6bc7-4802-8e9a-723ceca4bd8f")
        }
    }
}
