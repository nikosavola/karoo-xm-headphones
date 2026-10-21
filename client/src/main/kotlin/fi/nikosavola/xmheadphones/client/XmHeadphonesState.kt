package fi.nikosavola.xmheadphones.client

import fi.nikosavola.xmheadphones.protocol.AmbientLevel
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

    /**
     * Nothing paired looks like a headset. Pairing happens in Karoo's own Bluetooth settings rather
     * than from here, so this is the one link state a rider has to leave the screen to change.
     */
    NoPairedHeadset,

    /**
     * A paired headset has not answered. There is no scan that could find one: the control channel
     * exists only for a device that is already paired.
     */
    Searching,

    Connecting,

    Connected,
}

/**
 * What is wrong with the link, in terms a screen can say something useful about.
 *
 * The platform's exception does not separate "the headset is asleep" from "this is not a Sony
 * headset": both arrive as the same failed socket. These do, because the client can tell whether the
 * device it tried is already carrying audio on this phone. The platform's own words are kept for
 * whatever else goes wrong, since only it can explain some failures.
 */
public sealed interface HeadsetProblem {
    /** A paired device is there but nothing answered: it is switched off, out of reach, or asleep. */
    public data object NotConnected : HeadsetProblem

    /** Something is there, but it does not speak Sony's protocol. */
    public data object NotSony : HeadsetProblem

    /** Anything else, in the platform's own words. */
    public data class Platform(public val message: String) : HeadsetProblem
}

/**
 * Everything the headset has told us, as one value.
 *
 * Each field is null or absent until the headset answers the question that fills it, and keeps its
 * last value if the link drops, so a screen shows the last known state instead of blanking.
 * [capabilities] is what arrived in the handshake, and it is what a settings screen should use to
 * decide which controls to show. A headset that never advertised noise cancelling must not be offered
 * it.
 */
public data class XmHeadphonesState(
    public val link: HeadphonesLink = HeadphonesLink.Idle,
    public val device: SonyDeviceInfo = SonyDeviceInfo(),
    /**
     * The address of the headset the link is on, which is the one thing a rider can quote when
     * something is wrong. Null until a headset has answered.
     */
    public val address: String? = null,
    public val capabilities: SonyCapabilities? = null,
    public val battery: BatteryReading? = null,
    public val noiseCancelling: NoiseCancelling? = null,
    public val equalizerPreset: EqualizerPreset? = null,
    /**
     * The ambient level the rider last had, kept across a spell of noise cancelling or off.
     *
     * Neither of those states carries a level, so without this a slider or a mode change would start
     * from nothing instead of from where the rider left it.
     */
    public val lastAmbientLevel: AmbientLevel? = null,
    /**
     * The focus-on-voice the rider last had, kept across a spell of noise cancelling or off.
     *
     * Only ambient mode carries it, so without this, switching to noise cancelling and back would
     * clear a setting the rider never touched.
     */
    public val lastFocusOnVoice: Boolean? = null,
    /** The last thing that went wrong, kept for a settings screen instead of logged and lost. */
    public val lastError: HeadsetProblem? = null,
) {
    /** The number a ride screen shows. Earbuds report per bud, and the lower one runs out first. */
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
