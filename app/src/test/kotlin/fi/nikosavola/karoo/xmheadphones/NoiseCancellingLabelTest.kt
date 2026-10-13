package fi.nikosavola.karoo.xmheadphones

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolTable
import fi.nikosavola.xmheadphones.protocol.ProtocolVersion
import fi.nikosavola.xmheadphones.protocol.SonyCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The words a ride screen puts in the noise cancelling field.
 *
 * The connected but quiet case is the one worth pinning. It used to fall in with a headset that
 * offers no noise control at all, so the field read "Not supported" through the whole handshake of a
 * headset that does support it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NoiseCancellingLabelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a link that is not up says which one it is`() {
        assertEquals("No permission", label(HeadphonesLink.NoPermission))
        assertEquals("Bluetooth off", label(HeadphonesLink.BluetoothOff))
        assertEquals("No headset paired", label(HeadphonesLink.NoPairedHeadset))
        assertEquals("Searching…", label(HeadphonesLink.Searching))
        assertEquals("Searching…", label(HeadphonesLink.Connecting))
        assertEquals("Searching…", label(HeadphonesLink.Idle))
    }

    @Test
    fun `a reported mode is spelled out`() {
        assertEquals("Noise cancelling", label(connected(NoiseCancelling.Cancelling)))
        assertEquals("Off", label(connected(NoiseCancelling.Off)))
        assertEquals("Ambient sound", label(connected(ambient(focusOnVoice = false))))
        assertEquals("Ambient sound, voice", label(connected(ambient(focusOnVoice = true))))
    }

    @Test
    fun `a headset that offers noise control and has not reported yet is not called unsupported`() {
        assertEquals("Unknown", label(connected(noiseCancelling = null)))
    }

    @Test
    fun `a headset that offers no noise control says so`() {
        assertEquals("Not supported", label(connected(noiseCancelling = null, functionId = 0x11)))
    }

    private fun label(link: HeadphonesLink): String =
        noiseCancellingLabel(context, XmHeadphonesState(link = link))

    private fun label(state: XmHeadphonesState): String = noiseCancellingLabel(context, state)

    /** 0x62 is V1 noise cancelling; a headset advertising only 0x11 has a battery and nothing else. */
    private fun connected(noiseCancelling: NoiseCancelling?, functionId: Int = 0x62) =
        XmHeadphonesState(
            link = HeadphonesLink.Connected,
            capabilities =
                SonyCapabilities(ProtocolVersion.V1, setOf(functionId), setOf(ProtocolTable.Table1)),
            noiseCancelling = noiseCancelling,
        )

    private fun ambient(focusOnVoice: Boolean) =
        NoiseCancelling.Ambient(AmbientLevel.clamped(10), focusOnVoice)
}
