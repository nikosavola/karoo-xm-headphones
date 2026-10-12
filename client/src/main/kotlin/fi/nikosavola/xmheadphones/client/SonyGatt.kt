package fi.nikosavola.xmheadphones.client

import java.util.UUID

/**
 * The GATT table a Sony headset exposes for its control protocol, which Sony calls Tandem.
 *
 * The values come from Sony's own SongPal app: mos9527/SonyHeadphonesClient records them in
 * `libmdr/include/mdr-c/Base.h` as `ServiceUuid.TANDEM_OVER_BLE_HPC_SERVICE`, and Mercury000/SonyPods
 * has the same table read out of the Sound Connect APK. Everything a headset needs lives on the two
 * services below; the surrounding `5b833c9*` characteristics are part of Sony's transport setup and
 * are not used here.
 */
internal object SonyGatt {
    /** Table 1, which is where every command this client sends lives. */
    val SERVICE: UUID = UUID.fromString("5b833e20-6bc7-4802-8e9a-723ceca4bd8f")

    /** Table 2 on a V2 headset: multipoint, voice guidance and the rest of the second command set. */
    val SERVICE_TABLE2: UUID = UUID.fromString("5b833e21-6bc7-4802-8e9a-723ceca4bd8f")

    /**
     * The channel a V1 headset exposes instead. V1 devices reach this protocol over the classic
     * serial profile on a desktop; whether they answer on BLE through this service is not something
     * any of the reference implementations has been able to confirm, so it is tried and its silence
     * is reported rather than worked around.
     */
    val SERVICE_V1: UUID = UUID.fromString("5b833e23-6bc7-4802-8e9a-723ceca4bd8f")

    /** Phone to headset, table 1. Written without a response; the protocol's own ACK is the receipt. */
    val WRITE: UUID = UUID.fromString("5b833c60-6bc7-4802-8e9a-723ceca4bd8f")

    /** Headset to phone, table 1. */
    val NOTIFY: UUID = UUID.fromString("5b833c61-6bc7-4802-8e9a-723ceca4bd8f")

    /** Phone to headset, table 2. */
    val WRITE_TABLE2: UUID = UUID.fromString("5b833c62-6bc7-4802-8e9a-723ceca4bd8f")

    /** Headset to phone, table 2. */
    val NOTIFY_TABLE2: UUID = UUID.fromString("5b833c63-6bc7-4802-8e9a-723ceca4bd8f")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Advertised by a headset that is accepting a control connection. */
    val ADVERTISED_SERVICES: List<UUID> = listOf(SERVICE, SERVICE_TABLE2, SERVICE_V1)
}
