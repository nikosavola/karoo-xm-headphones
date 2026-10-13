package fi.nikosavola.xmheadphones.client

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a caller has to hold, per API level, before the client can reach a paired headset.
 *
 * Reading the paired devices and opening the channel to one is behind `BLUETOOTH_CONNECT` from
 * Android 12. Below it the platform's own install-time pair covers both, so nothing is asked for at
 * runtime, which the extension still ships to.
 */
class RequiredPermissionsTest {
    @Test
    fun `Android 12 and up need the Bluetooth connect permission`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT),
            XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.S),
        )
    }

    @Test
    fun `Android 11 and below have nothing to ask for`() {
        assertTrue(XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.R).isEmpty())
    }

    @Test
    fun `the oldest supported release needs the same as the rest up to Android 11`() {
        assertEquals(
            XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.R),
            XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.O),
        )
    }

    @Test
    fun `location is never asked for, on any release`() {
        val asked =
            listOf(Build.VERSION_CODES.O, Build.VERSION_CODES.R, Build.VERSION_CODES.S)
                .flatMap { XmHeadphonesClient.requiredPermissions(it) }
        assertTrue(
            "location is still asked for",
            asked.none { it == Manifest.permission.ACCESS_FINE_LOCATION },
        )
    }
}
