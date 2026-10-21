package fi.nikosavola.karoo.xmheadphones

import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a tap on the noise cancelling field moves to. */
class NoiseCancellingModesTest {
    @Test
    fun `nothing has a next mode until the headset reports one`() {
        assertNull(state().nextNoiseCancelling)
    }

    @Test
    fun `off moves to cancelling, cancelling to ambient, ambient to off`() {
        assertEquals(NoiseCancelling.Cancelling, state(NoiseCancelling.Off).nextNoiseCancelling)
        assertEquals(
            NoiseCancelling.Ambient(AmbientLevel.clamped(14), focusOnVoice = true),
            state(NoiseCancelling.Cancelling, lastLevel = 14, lastVoice = true).nextNoiseCancelling,
        )
        assertEquals(
            NoiseCancelling.Off,
            state(NoiseCancelling.Ambient(AmbientLevel.clamped(9), false)).nextNoiseCancelling,
        )
    }

    @Test
    fun `ambient starts from the level and voice the rider had`() {
        assertEquals(
            NoiseCancelling.Ambient(AmbientLevel.clamped(10), focusOnVoice = false),
            state(NoiseCancelling.Cancelling).nextNoiseCancelling,
        )
    }

    private fun state(
        mode: NoiseCancelling? = null,
        lastLevel: Int? = null,
        lastVoice: Boolean? = null,
    ) =
        XmHeadphonesState(
            link = HeadphonesLink.Connected,
            noiseCancelling = mode,
            lastAmbientLevel = lastLevel?.let(AmbientLevel::clamped),
            lastFocusOnVoice = lastVoice,
        )
}
