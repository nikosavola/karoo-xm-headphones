package fi.nikosavola.karoo.xmheadphones

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The client's permission list and the manifest have to agree, and agreement is per API level: the
 * manifest puts a `maxSdkVersion` on the location permission and none on the Bluetooth pair, so the
 * platform drops one below Android 12 and the other above it.
 *
 * A request for a permission the manifest does not declare is ignored without an error, which leaves
 * a rider on a screen saying a permission is needed with nothing that can satisfy it. That is what
 * this catches.
 */
@RunWith(RobolectricTestRunner::class)
class ManifestPermissionsTest {
    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun `an Android 8 device is asked only for permissions the manifest declares`() {
        assertDeclared(XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.O))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `an Android 13 device is asked only for permissions the manifest declares`() {
        assertDeclared(XmHeadphonesClient.requiredPermissions(Build.VERSION_CODES.TIRAMISU))
    }

    /** Whichever set applies, the other one has to be gone, or the app asks for a reason it does not have. */
    @Test
    @Config(sdk = [Build.VERSION_CODES.TIRAMISU])
    fun `the location permission is not declared where the Bluetooth pair is`() {
        assertFalse(
            "${Manifest.permission.ACCESS_FINE_LOCATION} is still declared on Android 13",
            declared().contains(Manifest.permission.ACCESS_FINE_LOCATION),
        )
    }

    private fun assertDeclared(asked: List<String>) {
        val declared = declared()
        asked.forEach {
            assertTrue("$it is asked for but not declared in the manifest", it in declared)
        }
    }

    private fun declared(): Set<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
    }
}
