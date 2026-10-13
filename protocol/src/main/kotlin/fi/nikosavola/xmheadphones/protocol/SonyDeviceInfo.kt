package fi.nikosavola.xmheadphones.protocol

/** Which device-info field [SonyHandshakeRequest.DeviceInfo] asks for. */
public enum class DeviceInfoQuestion(public val wire: Int) {
    ModelName(0x01),
    FirmwareVersion(0x02),
    SeriesAndColor(0x03),
}

/**
 * What the headset calls itself. The two fields arrive in separate replies and either can be
 * missing, so this fills in as each lands.
 */
public data class SonyDeviceInfo(
    public val modelName: String? = null,
    public val firmwareVersion: String? = null,
) {
    /** For a log line or a settings header. The MAC address is the identity, not this. */
    override fun toString(): String {
        val name = modelName ?: "Sony headphones"
        return firmwareVersion?.let { "$name (firmware $it)" } ?: name
    }
}
