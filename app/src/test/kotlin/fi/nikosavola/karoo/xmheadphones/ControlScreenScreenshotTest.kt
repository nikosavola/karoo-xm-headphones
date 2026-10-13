package fi.nikosavola.karoo.xmheadphones

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
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
 * Renders the control screen in the states it can be in and draws the README's images. They land in
 * the build directory; `./gradlew :app:recordScreenshots` is what copies them over the tracked ones.
 *
 * These are the real composables drawn with values such a headset would report, not drawings of the
 * screen; the states are set directly because no headset is available to reach them.
 *
 * The qualifiers are the 480x800 at 280 dpi the committed images were recorded at; the Karoo in hand
 * reports the same size at 300 dpi, so its viewport is a little narrower than an image shows. The
 * screen's content measures about 695dp against that 457dp, so it scrolls, and its lower half is
 * captured as its own image rather than left out.
 *
 * Each test checks what is on the screen before it captures: the texts it must show, and, where the
 * state has them, that the slider and the switch exist at all, which chip reads as selected, and
 * that no chip's label has been wrapped by a narrow row. Text alone is not enough to say an image is
 * right, and the first version of this file published a screen whose chips had been squeezed to one
 * letter per line, which no amount of asserting text could see.
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
                "Headset battery: 82%",
                "Ambient level: 14",
                "Focus on voice",
            )
            // The current preset is named above the chips and selected among them.
            showsTimes("Rock", times = 2)
            hasTheControls()
            chipsFitOnOneLine("Off", "Noise cancelling", "Ambient", "Rock", "Pop", "Jazz")
            compose.onNodeWithText("Ambient").assertIsSelected()
        }
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
            shows("Headset battery: 62%")
            // The section header and the chip that is selected in it.
            showsTimes("Noise cancelling", times = 2)
            compose.onAllNodesWithText("Noise cancelling")[1].assertIsSelected()
            chipsFitOnOneLine("Off", "Noise cancelling", "Ambient")
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

    @Test
    fun `renders a headset waiting for permission`() {
        record("control-permission", XmHeadphonesState(link = HeadphonesLink.NoPermission)) {
            shows("Bluetooth permission is needed to find the headset.", "Grant permission")
            // The sections leave the blocker to the connection row rather than each claiming a
            // headset is being waited for when nothing is looking for one.
            showsTimes("Waiting for a headset.", times = 0)
        }
    }

    /**
     * The two changes a rider makes inside one round trip have to build on each other.
     *
     * The level is dragged and focus on voice is switched before the headset has answered either.
     * The reported state still says the old level, so a screen that built the second change on it
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
            MaterialTheme {
                ControlScreen(state.value, { sent += it }, {}, {}, {})
            }
        }
        compose.waitForIdle()

        // The first "Off" in the tree is the noise control chip; the equalizer has one too.
        compose.onAllNodesWithText("Off").onFirst().performClick()
        compose.waitForIdle()
        // The headset answers, confirming the mode it was moved to. Only now is there nothing left
        // in the state that remembers focus on voice except what the client kept.
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
     * A chip is as wide as its label needs. A row that has run out of width squeezes the last chip
     * instead of moving it down, and Material3 then wraps the label one letter per line: the chip
     * gets several times taller than the 32dp a single line needs, which is what this catches.
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
            // Lossless rather than quality 100: this is flat UI text and fills, where a lossy
            // encoder spends its bits on nothing and still leaves ringing around the glyphs.
            @Suppress("InlinedApi")
            val written = bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
            assertTrue("could not write $name", written)
        }
    }

    private fun render(state: XmHeadphonesState, setNoiseCancelling: (NoiseCancelling) -> Unit) {
        compose.setContent {
            MaterialTheme {
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

        /** A Material3 chip is 32dp; a two line label makes it over 50. */
        val ONE_LINE_CHIP: Dp = 40.dp

        /** V2 ids for a single battery, an equalizer and the dual-mode noise control. */
        const val BATTERY_V2 = 0x20
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
        ): XmHeadphonesState =
            XmHeadphonesState(
                link = HeadphonesLink.Connected,
                device = SonyDeviceInfo(modelName = modelName, firmwareVersion = firmwareVersion),
                capabilities = SonyCapabilities(ProtocolVersion.V2, functions),
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
