package fi.nikosavola.xmheadphones.client

import java.util.UUID

/**
 * The GATT table a Sony headset exposes for its control protocol, which Sony calls Tandem.
 *
 * These are Sony's own UUIDs, out of the SongPal app: `ServiceUuid.TANDEM_OVER_BLE_HPC_SERVICE` and
 * `CharacteristicUuid.TANDEM_HPC_TO_ACC` / `TANDEM_HPC_FROM_ACC`. mos9527/SonyHeadphonesClient
 * records the same service, as `MDR_SERVICE_UUID_BLE` in `libmdr/include/mdr-c/Base.h`.
 *
 * Only the first table is spoken to. A V2 headset also exposes a second service,
 * `5b833e21-6bc7-4802-8e9a-723ceca4bd8f`, which carries multipoint and voice guidance, and the
 * characteristics either side of these ones are part of Sony's transport setup rather than its
 * commands. Nothing here talks to either, so neither is listed.
 */
internal object SonyGatt {
    /** Table 1, which is where every command this client sends lives. */
    val SERVICE: UUID = UUID.fromString("5b833e20-6bc7-4802-8e9a-723ceca4bd8f")

    /** Phone to headset. Written without a response; the protocol's own ACK is the receipt. */
    val WRITE: UUID = UUID.fromString("5b833c60-6bc7-4802-8e9a-723ceca4bd8f")

    /** Headset to phone. */
    val NOTIFY: UUID = UUID.fromString("5b833c61-6bc7-4802-8e9a-723ceca4bd8f")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
