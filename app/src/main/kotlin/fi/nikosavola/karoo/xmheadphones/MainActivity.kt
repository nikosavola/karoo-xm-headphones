package fi.nikosavola.karoo.xmheadphones

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import kotlinx.coroutines.launch

/**
 * Hosts [ControlScreen] and wires it to the headset connection.
 *
 * Nothing about the screen's appearance lives here: it is kept to permissions, the client's
 * lifetime, and the callbacks that carry a command out.
 */
class MainActivity : ComponentActivity() {
    private val client by lazy { XmHeadphonesClient.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

        setContent {
            MaterialTheme {
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

    // The client is reference counted, so holding one here while the screen is open is enough; the
    // link lingers afterwards rather than being torn down the moment the rider leaves the screen.
    override fun onStart() {
        super.onStart()
        client.start()
    }

    override fun onStop() {
        super.onStop()
        client.stop()
    }

    // The list comes from the client rather than being written out again here: the client is what
    // refuses to scan, so a second copy that drifted would leave the button asking for something the
    // scan does not accept, and the state stuck on "permission is needed" with nothing left to grant.
    private fun missingPermissions(): Array<String> =
        XmHeadphonesClient.requiredPermissions()
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()
}
