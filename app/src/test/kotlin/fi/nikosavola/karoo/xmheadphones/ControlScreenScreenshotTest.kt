package fi.nikosavola.karoo.xmheadphones

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.BatteryReading
import fi.nikosavola.xmheadphones.protocol.BatteryStatus
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolTable
import fi.nikosavola.xmheadphones.protocol.ProtocolVersion
import fi.nikosavola.xmheadphones.protocol.SonyCapabilities
import fi.nikosavola.xmheadphones.protocol.SonyDeviceInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the control screen in every state it can be in and draws the README's images. They land in
 * the build directory, and `./gradlew :app:recordScreenshots` copies them over the tracked ones.
 *
 * These are the real composables, drawn with values a headset would report, not drawings of the
 * screen. The states are set directly because no headset is available to reach them.
 *
 * The qualifiers are the 480x800 at 280 dpi the committed images were recorded at. The Karoo in hand
 * reports the same size at 300 dpi, so its viewport is a little narrower than an image shows. The
 * content measures about 695dp against that 457dp, so the screen scrolls, and the lower half is
 * captured as its own image.
 *
 * Each test checks the screen before capturing. That means the texts it must show, and where the
 * state has them, that the slider and the switch exist, which chip is selected, and that no label
 * was wrapped by a narrow row. Text alone cannot say an image is right. The first version of this
 * file published a screen with chips squeezed to one letter per line, which no text assertion
 * could see.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w274dp-h457dp-280dpi")
class ControlScreenScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `renders ambient mode with a level`() {
        record(
            "control-ambient",
            connected(
                noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = false),
                equalizerPreset = EqualizerPreset.Rock,
            ),
        ) {
            shows(
                "WH-1000XM5 (firmware 2.1.0)",
                "Headset battery",
                "82%",
                "Ambient level: 14",
                "Focus on voice",
            )
            // The current preset is named above the chips and selected among them.
            showsTimes("Rock", times = 2)
            hasTheControls()
            modesOnOneRow()
            nameIsOnOneLine("WH-1000XM5 (firmware 2.1.0)")
            chipsFitOnOneLine("Rock", "Pop", "Jazz")
            compose.onNodeWithText("Ambient").assertIsSelected()
        }
    }

    /**
     * The chip rows at the width the Karoo itself has.
     *
     * The committed images are 274dp wide. The Karoo reports the same 480x800 at 300 dpi, which is
     * 256dp, so its rows have 18dp less to fit the same chips in. A row that only just fits at 274
     * squeezes its last chip into a column of single letters here, and this is the width the rider
     * actually sees. No image is written: the committed ones stay at the dpi they were recorded at.
     */
    @Test
    @Config(qualifiers = "w256dp-h427dp-300dpi")
    fun `the chip rows still fit on one line at the Karoo's own width`() {
        render(
            connected(
                noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = false),
                equalizerPreset = EqualizerPreset.Rock,
            ),
        ) {}

        // Pinned so the test cannot quietly go back to the wider viewport and keep passing.
        assertEquals(256.dp, compose.onNodeWithTag(SCREEN).getUnclippedBoundsInRoot().right)

        modesOnOneRow()
        nameIsOnOneLine("WH-1000XM5 (firmware 2.1.0)")
        chipsFitOnOneLine("Rock", "Pop", "Jazz")

        // The details are the tightest thing on the screen: a label and a value sharing one row, and
        // the address is the longest value any headset produces. At 274dp this fits with room to
        // spare and says nothing about the width the rider actually has.
        compose.onNodeWithText("WH-1000XM5 (firmware 2.1.0)").performClick()
        compose.waitForIdle()
        detailsFitOnOneLine(
            "00:11:22:33:44:55",
            "v2 layout",
            "WH-1000XM5",
            "2.1.0",
            "MDR v2",
            "1, 2",
        )
        detailLabelsHaveRoom("Command tables", "Noise control", "Equalizer presets")
    }

    @Test
    fun `renders the equalizer and forgetting below the fold`() {
        record(
            "control-ambient-lower",
            connected(
                noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = false),
                equalizerPreset = EqualizerPreset.Rock,
            ),
            scrollTo = "Forget headset",
        ) {
            shows("Equalizer", "Forget headset")
            // The connection at the top has scrolled off, so this really is the lower half.
            compose.onNodeWithText("WH-1000XM5 (firmware 2.1.0)").assertIsNotDisplayed()
        }
    }

    @Test
    fun `renders noise cancelling`() {
        record(
            "control-cancelling",
            connected(
                noiseCancelling = NoiseCancelling.Cancelling,
                equalizerPreset = EqualizerPreset.Off,
                batteryPercent = 62,
            ),
        ) {
            shows("62%")
            // The header spells the mode out; the segment it points at carries the short label.
            showsTimes("Noise cancelling", times = 1)
            compose.onNodeWithText("NC").assertIsSelected()
            modesOnOneRow()
        }
    }

    @Test
    fun `renders a headset without noise control`() {
        record(
            "control-unsupported",
            connected(
                noiseCancelling = null,
                equalizerPreset = null,
                functions = setOf(BATTERY_V2),
                modelName = null,
                firmwareVersion = null,
            ),
        ) {
            shows(
                "This headset does not offer noise cancelling.",
                "This headset does not offer equalizer presets.",
            )
        }
    }

    @Test
    fun `renders the search before a headset has answered`() {
        record("control-searching", XmHeadphonesState(link = HeadphonesLink.Searching)) {
            shows("Searching…")
            // Nothing has said what this headset can do, so the sections must not claim it cannot.
            showsTimes("Waiting for a headset.", times = 2)
        }
    }

    /**
     * The first-run screen, which is the one a rider meets before anything works.
     *
     * Nothing paired is not the same as a paired headset that has gone quiet: the fix is a step in
     * Karoo's own Bluetooth settings, and a screen that only said it was searching left them with
     * nothing to try.
     */
    @Test
    fun `renders the search with nothing paired`() {
        record("control-no-paired", XmHeadphonesState(link = HeadphonesLink.NoPairedHeadset)) {
            shows(
                "No headset paired",
                "Pair the headset in Karoo's Bluetooth settings and it will be picked up here.",
            )
            showsTimes("Searching…", times = 0)
        }
    }

    /**
     * What went wrong is worth a line of its own, rather than being logged and lost.
     *
     * A headset that answers and is refused over a disabled command table, or a channel that will
     * not open at all, leaves the link down with nothing else on the screen to say which of the two
     * it was.
     */
    @Test
    fun `a link that failed says what the headset or the platform said`() {
        render(
            XmHeadphonesState(
                link = HeadphonesLink.Searching,
                lastError = "the headset never reported its protocol version",
            ),
        ) {}

        shows("Last error: the headset never reported its protocol version")
    }

    /**
     * The two lines the connection row can gain, at the width the Karoo itself has.
     *
     * Both are prose and are meant to wrap, so what matters is not that they are one line but that
     * neither takes the screen sideways. The error is the one text on this screen this app did not
     * write: it is the headset's or the platform's own words, and a long one is possible.
     */
    @Test
    @Config(qualifiers = "w256dp-h427dp-300dpi")
    fun `what a link that is down says fits the Karoo's own width`() {
        render(
            XmHeadphonesState(
                link = HeadphonesLink.NoPairedHeadset,
                lastError =
                    "the headset reports protocol V2 with table 1 disabled; the commands this " +
                        "client sends all live there",
            ),
        ) {}

        val screen = compose.onNodeWithTag(SCREEN).getUnclippedBoundsInRoot()
        listOf(
            "No headset paired",
            "Pair the headset in Karoo's Bluetooth settings and it will be picked up here.",
            "Last error: the headset reports protocol V2 with table 1 disabled; the commands " +
                "this client sends all live there",
        )
            .forEach {
                val bounds = compose.onNodeWithText(it).getUnclippedBoundsInRoot()
                assertTrue(
                    "$it reaches ${bounds.right}, past the ${screen.right} screen",
                    bounds.right <= screen.right,
                )
            }
    }

    @Test
    fun `renders a headset waiting for permission`() {
        record("control-permission", XmHeadphonesState(link = HeadphonesLink.NoPermission)) {
            shows("Bluetooth permission is needed to find the headset.", "Grant permission")
            // The sections leave the blocker to the connection row. Otherwise each would claim to
            // wait for a headset when nothing is looking for one, or to wait on a mode from a
            // headset that has not been looked for at all.
            showsTimes("Waiting for a headset.", times = 0)
            showsTimes("Waiting for the headset to report its mode.", times = 0)
            // The controls are drawn but inert. With no link there is nothing to send to, and a
            // press would leave the screen showing a mode no headset ever took.
            shows("NC")
            compose.onNodeWithText("NC").assertIsNotEnabled()
            compose.onAllNodesWithText("Rock").onFirst().assertIsNotEnabled()
        }
    }

    /**
     * What the handshake found out, behind the headset's name.
     *
     * Every value here is one the headset reported. That is the point: when a control does nothing,
     * which layout was written is the first question, and a rider cannot answer it from the mode
     * labels alone.
     */
    @Test
    fun `the headset name opens what the handshake found out`() {
        record(
            "control-details",
            connected(
                noiseCancelling = NoiseCancelling.Cancelling,
                equalizerPreset = EqualizerPreset.Rock,
            ),
        ) {
            compose.onNodeWithText("WH-1000XM5 (firmware 2.1.0)").performClick()
            compose.waitForIdle()

            shows(
                "Model",
                "Firmware",
                "Address",
                "Protocol",
                "Command tables",
                "Battery",
                "Noise control",
                "Equalizer presets",
                "MDR v2",
                "v2 layout",
                "00:11:22:33:44:55",
                "1, 2",
                "Single",
            )
            nameIsOnOneLine("WH-1000XM5 (firmware 2.1.0)")
            detailsFitOnOneLine("00:11:22:33:44:55", "v2 layout", "1, 2", "MDR v2")
            detailLabelsHaveRoom("Command tables", "Noise control", "Equalizer presets")
        }
    }

    /** Tapping the name again puts the panel away, rather than leaving the screen taller for good. */
    @Test
    fun `the headset name puts the details away again`() {
        render(connected(noiseCancelling = NoiseCancelling.Cancelling, equalizerPreset = EqualizerPreset.Rock)) {}

        compose.onNodeWithText("WH-1000XM5 (firmware 2.1.0)").performClick()
        compose.waitForIdle()
        shows("Protocol")

        compose.onNodeWithText("WH-1000XM5 (firmware 2.1.0)").performClick()
        compose.waitForIdle()
        showsTimes("Protocol", times = 0)
    }

    /**
     * With no headset on the link there is nothing to describe, so the name is not a control.
     *
     * Left live it would open a panel of rows that are all absent, or worse, of the last headset's
     * values, which are not the ones on the link.
     */
    @Test
    fun `the name offers no details with no headset on the link`() {
        render(XmHeadphonesState(link = HeadphonesLink.Searching)) {}

        showsTimes("Protocol", times = 0)
        compose.onNodeWithText("Searching…").assertIsNotEnabled()
    }

    /**
     * A field the headset never answered has no row, rather than one reading empty.
     *
     * The absence is the information: a headset that never reported its model is not the same as one
     * whose model is blank, and an empty value beside a label says neither.
     */
    @Test
    fun `a field the headset never answered has no row`() {
        render(
            connected(
                noiseCancelling = null,
                equalizerPreset = null,
                functions = setOf(BATTERY_V2),
                modelName = null,
                firmwareVersion = null,
                address = null,
                tables = emptySet(),
            ),
        ) {}

        compose.onNodeWithText("Sony headphones").performClick()
        compose.waitForIdle()

        showsTimes("Model", times = 0)
        showsTimes("Address", times = 0)
        // Tables are the one field that answers with an empty set rather than with nothing, and an
        // empty set is a thing to say: it is a headset reporting no table open, not a headset that
        // was never asked. A V1 headset, which the codec gives table 1 to because its reply carries
        // no flags at all, is a different row.
        shows("Protocol", "MDR v2", "Command tables", "None", "Equalizer presets", "No")
    }

    /**
     * The two changes a rider makes inside one round trip must build on each other.
     *
     * The level is dragged and focus on voice switched before the headset answers either. The
     * reported state still carries the old level, so a screen building the second change on it
     * would send the level back with the voice setting.
     */
    @Test
    fun `a change made before the headset answers builds on the one before it`() {
        val sent = mutableListOf<NoiseCancelling>()
        render(
            connected(
                noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(10), focusOnVoice = false),
                equalizerPreset = EqualizerPreset.Rock,
            ),
        ) { sent += it }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(14f) }
        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(
                NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = false),
                NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = true),
            ),
            sent,
        )
    }

    /**
     * A state change with nothing to do with noise control must not drop a change in flight.
     *
     * The battery poll replaces the whole state. Keying the pending change off all of it made the
     * poll clear the change, so a rider who dragged the level and flipped focus a moment later sent
     * the level the headset last reported rather than the one they had just chosen.
     */
    @Test
    fun `an unrelated state change does not drop a change the headset has not answered yet`() {
        val sent = mutableListOf<NoiseCancelling>()
        val state =
            mutableStateOf(
                connected(
                    noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(10), focusOnVoice = false),
                    equalizerPreset = EqualizerPreset.Rock,
                    batteryPercent = 82,
                ),
            )
        compose.setContent {
            XmHeadphonesTheme {
                ControlScreen(state.value, { sent += it }, {}, {}, {})
            }
        }
        compose.waitForIdle()

        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(14f) }
        compose.waitForIdle()

        // The poll lands carrying the old mode, which is not an answer to the drag.
        state.value =
            state.value.copy(battery = BatteryReading.Single(BatteryStatus.fromDevice(81, false)))
        compose.waitForIdle()

        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(
                NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = false),
                NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = true),
            ),
            sent,
        )
    }

    /**
     * The gauge's colour is the part no text assertion can see.
     *
     * It is read back from the drawn pixels rather than from the theme, so a palette change that
     * leaves a nearly flat headset looking as ordinary as a full one fails here.
     */
    @Test
    fun `the battery gauge turns red as the headset runs out`() {
        val state =
            mutableStateOf(
                connected(
                    noiseCancelling = NoiseCancelling.Cancelling,
                    equalizerPreset = EqualizerPreset.Rock,
                    batteryPercent = 90,
                ),
            )
        compose.setContent {
            XmHeadphonesTheme {
                Box(modifier = Modifier.fillMaxSize().testTag(SCREEN)) {
                    ControlScreen(state.value, {}, {}, {}, {})
                }
            }
        }
        compose.waitForIdle()
        val charged = brightestGreen()

        state.value =
            state.value.copy(
                battery = BatteryReading.Single(BatteryStatus.fromDevice(5, charging = false)),
            )
        compose.waitForIdle()
        val nearlyFlat = brightestGreen()

        assertTrue(
            "the gauge draws $nearlyFlat green when nearly flat and $charged when charged",
            nearlyFlat < charged - 40,
        )
    }

    /** The most green any pixel of the gauge carries, which is the colour it was drawn in. */
    private fun brightestGreen(): Int =
        compose
            .onNodeWithTag(BATTERY_GAUGE)
            .captureToImage()
            .toPixelMap()
            .let { pixels ->
                (0 until pixels.width).maxOf { x ->
                    (0 until pixels.height).maxOf { y -> (pixels[x, y].green * 255).toInt() }
                }
            }

    /** Focus on voice is a setting of the rider's, not of the mode they happen to be in. */
    @Test
    fun `focus on voice survives a trip through off and back`() {
        val sent = mutableListOf<NoiseCancelling>()
        val state =
            mutableStateOf(
                connected(
                    noiseCancelling = NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = true),
                    equalizerPreset = EqualizerPreset.Rock,
                    lastFocusOnVoice = true,
                ),
            )
        compose.setContent {
            XmHeadphonesTheme {
                ControlScreen(state.value, { sent += it }, {}, {}, {})
            }
        }
        compose.waitForIdle()

        // The first "Off" in the tree is the noise control chip. The equalizer has one too.
        compose.onAllNodesWithText("Off").onFirst().performClick()
        compose.waitForIdle()
        // The headset confirms the new mode. Only now is nothing left in the state that remembers
        // focus on voice, except what the client kept.
        state.value = state.value.copy(noiseCancelling = NoiseCancelling.Off)
        compose.waitForIdle()

        compose.onNodeWithText("Ambient").performClick()
        compose.waitForIdle()

        assertEquals(
            listOf(
                NoiseCancelling.Off,
                NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = true),
            ),
            sent,
        )
    }

    /** Every one of these has to be visible for the image to be worth publishing. */
    private fun shows(vararg texts: String) {
        texts.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    /** For labels the screen legitimately shows more than once, and once for none at all. */
    private fun showsTimes(text: String, times: Int) {
        compose.onAllNodesWithText(text).assertCountEquals(times)
    }

    /** The controls whose absence a text assertion would not notice. */
    private fun hasTheControls() {
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertExists()
        compose.onNode(isToggleable()).assertExists()
    }

    /**
     * The three modes share one row, and a label that did not fit its third of it would wrap and
     * push the row past the 40dp a single line of segments takes.
     */
    private fun modesOnOneRow() {
        val screen = compose.onNodeWithTag(SCREEN).getUnclippedBoundsInRoot()
        val modes =
            listOf(
                compose.onAllNodesWithText("Off").onFirst(),
                compose.onNodeWithText("NC"),
                compose.onNodeWithText("Ambient"),
            ).map { it.getUnclippedBoundsInRoot() }
        modes.forEach {
            assertTrue(
                "a mode is ${it.bottom - it.top} tall, so its label wrapped",
                it.bottom - it.top <= ONE_LINE_MODES,
            )
            assertTrue("a mode reaches ${it.right}, past the ${screen.right} screen", it.right <= screen.right)
        }
        assertEquals("the modes are not all on one row", 1, modes.map { it.top }.distinct().size)
    }

    /**
     * A chip is as wide as its label needs. A row out of width squeezes the last chip rather than
     * moving it down, and Material3 then wraps the label one letter per line. The chip grows several
     * times taller than the 32dp a single line needs, which is what this catches.
     */
    private fun chipsFitOnOneLine(vararg labels: String) {
        val screen = compose.onNodeWithTag(SCREEN).getUnclippedBoundsInRoot()
        labels.forEach { label ->
            val nodes = compose.onAllNodesWithText(label)
            repeat(nodes.fetchSemanticsNodes().size) { index ->
                val bounds = nodes[index].getUnclippedBoundsInRoot()
                assertTrue(
                    "$label is ${bounds.bottom - bounds.top} tall, so its label wrapped",
                    bounds.bottom - bounds.top <= ONE_LINE_CHIP,
                )
                assertTrue(
                    "$label reaches ${bounds.right}, past the ${screen.right} screen",
                    bounds.right <= screen.right,
                )
            }
        }
    }

    /**
     * The headset name is a model and a firmware version, and on a Karoo it takes very nearly the
     * whole row. Anything else sharing that row takes enough to wrap it, which no text assertion
     * sees and which shows up as a connection line broken across two.
     */
    private fun nameIsOnOneLine(name: String) {
        // The unmerged tree, because the row around it is clickable and a clickable node is padded
        // out to the 48dp a finger needs. That is taller than two lines of text, so the merged node
        // says nothing about whether the name itself wrapped.
        val bounds = compose.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "the headset name is ${bounds.bottom - bounds.top} tall, so it wrapped",
            bounds.bottom - bounds.top <= ONE_LINE_NAME,
        )
    }

    /** A value that did not fit its half of the row wraps, and takes the row past one line. */
    private fun detailsFitOnOneLine(vararg values: String) {
        val screen = compose.onNodeWithTag(SCREEN).getUnclippedBoundsInRoot()
        values.forEach { value ->
            val bounds = compose.onNodeWithText(value).getUnclippedBoundsInRoot()
            assertTrue(
                "$value is ${bounds.bottom - bounds.top} tall, so it wrapped",
                bounds.bottom - bounds.top <= ONE_LINE_DETAIL,
            )
            assertTrue(
                "$value reaches ${bounds.right}, past the ${screen.right} screen",
                bounds.right <= screen.right,
            )
        }
    }

    /**
     * A label with no room is the other half of the same failure, and the harder one to see.
     *
     * This does not guard the layout as it stands. The label is unweighted and the value carries the
     * weight, so the label is measured first and the value wraps instead: it cannot be crowded out.
     * What it holds is the arrangement, because the obvious way to rewrite [DetailRow], giving the
     * value priority, reintroduces a column of values belonging to nothing.
     */
    private fun detailLabelsHaveRoom(vararg labels: String) {
        labels.forEach { label ->
            val bounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue(
                "$label is only ${bounds.right - bounds.left} wide, so its value crowded it out",
                bounds.right - bounds.left >= MIN_LABEL,
            )
        }
    }

    private fun record(
        name: String,
        state: XmHeadphonesState,
        scrollTo: String? = null,
        shows: () -> Unit,
    ) {
        render(state) {}
        scrollTo?.let {
            compose.onNodeWithText(it).performScrollTo()
            compose.waitForIdle()
        }
        shows()

        val bitmap = compose.onNodeWithTag(SCREEN).captureToImage().asAndroidBitmap()
        val directory = File(screenshotDirectory())
        assertTrue("could not create $directory", directory.isDirectory || directory.mkdirs())
        File(directory, "$name.webp").outputStream().use { out ->
            // Lossless rather than quality 100. This is flat UI text and fills, where a lossy
            // encoder spends its bits on nothing and still leaves ringing around the glyphs.
            @Suppress("InlinedApi")
            val written = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            assertTrue("could not write $name", written)
        }
    }

    private fun render(state: XmHeadphonesState, setNoiseCancelling: (NoiseCancelling) -> Unit) {
        compose.setContent {
            XmHeadphonesTheme {
                Box(modifier = Modifier.fillMaxSize().testTag(SCREEN)) {
                    ControlScreen(
                        state = state,
                        setNoiseCancelling = setNoiseCancelling,
                        setEqualizerPreset = {},
                        forget = {},
                        askForPermissions = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The build default too, for a run that comes from somewhere other than Gradle. */
    private fun screenshotDirectory(): String =
        System.getProperty("xmheadphones.screenshotDir") ?: "build/screenshots"

    private companion object {
        const val SCREEN = "screen"

        /** A Material3 chip is 32dp, and a two line label makes it over 50. */
        val ONE_LINE_CHIP: Dp = 40.dp

        /** A Material3 segment is 48dp tall. A wrapped label takes it to about 64. */
        val ONE_LINE_MODES: Dp = 56.dp

        /** labelMedium is 12sp on a 16sp line, so a wrapped detail value is well past this. */
        val ONE_LINE_DETAIL: Dp = 22.dp

        /** The name is bodyMedium on a 20sp line, so a second line takes it past twice this. */
        val ONE_LINE_NAME: Dp = 30.dp

        /** Under this the label has been squeezed by its value, not merely wrapped. */
        val MIN_LABEL: Dp = 70.dp

        /** The ids a headset reports for a battery indicator, an equalizer and the dual-mode noise control. */
        const val BATTERY_V2 = 0x11
        const val EQUALIZER_V2 = 0x50
        const val NOISE_CONTROL_V2 = 0x6B

        fun connected(
            noiseCancelling: NoiseCancelling?,
            equalizerPreset: EqualizerPreset?,
            functions: Set<Int> = setOf(BATTERY_V2, EQUALIZER_V2, NOISE_CONTROL_V2),
            batteryPercent: Int = 82,
            lastFocusOnVoice: Boolean? = false,
            modelName: String? = "WH-1000XM5",
            firmwareVersion: String? = "2.1.0",
            address: String? = "00:11:22:33:44:55",
            tables: Set<ProtocolTable> = setOf(ProtocolTable.Table1, ProtocolTable.Table2),
        ): XmHeadphonesState =
            XmHeadphonesState(
                link = HeadphonesLink.Connected,
                device = SonyDeviceInfo(modelName = modelName, firmwareVersion = firmwareVersion),
                address = address,
                capabilities = SonyCapabilities(ProtocolVersion.V2, functions, tables),
                battery =
                    BatteryReading.Single(
                        BatteryStatus.fromDevice(batteryPercent, charging = false),
                    ),
                noiseCancelling = noiseCancelling,
                equalizerPreset = equalizerPreset,
                lastAmbientLevel = AmbientLevel.clamped(14),
                lastFocusOnVoice = lastFocusOnVoice,
            )
    }
}
