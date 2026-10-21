package fi.nikosavola.karoo.xmheadphones

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import kotlinx.coroutines.launch

/**
 * Hosts [ControlScreen] and wires it to the headset connection.
 *
 * Permissions, the client's lifetime, and the outgoing callbacks. Nothing about appearance.
 */
class MainActivity : ComponentActivity() {
    private val client by lazy { XmHeadphonesClient.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

        setContent {
            XmHeadphonesTheme {
                val state by client.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                ControlScreen(
                    state = state,
                    setNoiseCancelling = { target ->
                        scope.launch { client.setNoiseCancelling(target) }
                    },
                    setEqualizerPreset = { preset ->
                        scope.launch { client.setEqualizerPreset(preset) }
                    },
                    forget = client::forget,
                    askForPermissions = {
                        val missing = missingPermissions()
                        if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                    },
                )
            }
        }
    }

    // Reference counted, so one hold for the screen's lifetime is enough. The link lingers
    // after the rider leaves.
    override fun onStart() {
        super.onStart()
        client.start()
    }

    override fun onStop() {
        super.onStop()
        client.stop()
    }

    // The client owns this list, since it is what refuses to work without it. A drifted copy
    // would ask for something the client cannot use, leaving "permission is needed" with nothing
    // to grant.
    private fun missingPermissions(): Array<String> =
        XmHeadphonesClient.requiredPermissions()
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()
}
