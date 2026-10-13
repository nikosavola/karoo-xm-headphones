package fi.nikosavola.xmheadphones.client

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Both branches of the permission choice are devices this client runs on: Karoo 2 is Android 8 and
 * Karoo 3 is Android 13, and the two need different permissions to see a scan result at all.
 */
class RequiredPermissionsTest {
    @Test
    fun `Android 11 and below need location`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.R),
        )
    }

    @Test
    fun `the oldest supported release needs the same as the rest up to Android 11`() {
        assertEquals(
            XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.R),
            XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.O),
        )
    }

    @Test
    fun `Android 12 and up need the Bluetooth pair instead`() {
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.S),
        )
    }

    @Test
    fun `location is not asked for on Android 13, where the manifest no longer declares it`() {
        val asked = XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.TIRAMISU)
        assertEquals(false, asked.contains(Manifest.permission.ACCESS_FINE_LOCATION))
    }
}
