package fi.nikosavola.xmheadphones.client

import fi.nikosavola.xmheadphones.protocol.BatteryReading
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.SonyCapabilities
import fi.nikosavola.xmheadphones.protocol.SonyDeviceInfo

/** How far along the connection is. */
public enum class HeadphonesLink {
    /** Nobody has asked for the headset yet. */
    Idle,

    NoPermission,

    BluetoothOff,

    /** Looking for the headset; it answers only while it is willing to be connected to. */
    Searching,

    Connecting,

    Connected,
}

/**
 * Everything the headset has told us, as one value.
 *
 * Each field is null or absent until the headset has answered the question that fills it, and stays
 * at its last value if the link drops, so a screen shows the last known state rather than blanking.
 * [capabilities] is what arrived in the handshake and is what a settings screen should use to decide
 * which controls to show: a headset that never advertised noise cancelling must not be offered it.
 */
public data class XmHeadphonesState(
    public val link: HeadphonesLink = HeadphonesLink.Idle,
    public val device: SonyDeviceInfo = SonyDeviceInfo(),
    public val capabilities: SonyCapabilities? = null,
    public val battery: BatteryReading? = null,
    public val noiseCancelling: NoiseCancelling? = null,
    public val equalizerPreset: EqualizerPreset? = null,
    /** The last thing that went wrong, kept for a settings screen rather than logged and lost. */
    public val lastError: String? = null,
) {
    /** The number a ride screen shows: earbuds report per bud and the lower one runs out first. */
    public val batteryPercent: Int?
        get() =
            when (val reading = battery) {
                null -> null
                is BatteryReading.Single -> reading.status.percent
                is BatteryReading.LeftRight -> reading.lowest.percent
            }

    public val supportsNoiseCancelling: Boolean
        get() = capabilities?.noiseCancelling != null

    public val supportsEqualizer: Boolean
        get() = capabilities?.supportsEqualizerPreset == true
}
