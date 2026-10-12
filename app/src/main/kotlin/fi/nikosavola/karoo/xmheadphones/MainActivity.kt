package fi.nikosavola.karoo.xmheadphones

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesClient
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * The headset controls, as an ordinary app screen.
 *
 * Karoo's own settings cannot hold a slider or a list of presets, so everything the Sound Connect
 * app offers lives here and only the fields a rider glances at during a ride go on a page.
 */
class MainActivity : ComponentActivity() {
    private val client by lazy { XmHeadphonesClient.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControlScreen(
                        client = client,
                        askForPermissions = {
                            val missing = missingPermissions()
                            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                        },
                    )
                }
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

    private fun missingPermissions(): Array<String> {
        val needed =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        return needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
            .toTypedArray()
    }
}

@Composable
private fun ControlScreen(
    client: XmHeadphonesClient,
    askForPermissions: () -> Unit,
) {
    val state by client.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Connection(state, onAskForPermissions = askForPermissions)

        HorizontalDivider()
        NoiseControlSection(state, client, scope)

        HorizontalDivider()
        EqualizerSection(state, client, scope)

        HorizontalDivider()
        Forgetting(client)
    }
}

@Composable
private fun Connection(state: XmHeadphonesState, onAskForPermissions: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_connection), style = MaterialTheme.typography.titleMedium)
        Text(
            when (state.link) {
                HeadphonesLink.Connected -> state.device.toString()
                HeadphonesLink.Searching -> stringResource(R.string.state_searching)
                HeadphonesLink.Connecting -> stringResource(R.string.settings_connecting)
                HeadphonesLink.NoPermission -> stringResource(R.string.settings_no_permission)
                HeadphonesLink.BluetoothOff -> stringResource(R.string.settings_bluetooth_off)
                HeadphonesLink.Idle -> stringResource(R.string.settings_idle)
            },
        )
        state.batteryPercent?.let { Text(stringResource(R.string.settings_battery, it)) }
        if (state.link == HeadphonesLink.NoPermission) {
            Button(onClick = onAskForPermissions) {
                Text(stringResource(R.string.settings_grant_permission))
            }
        }
    }
}

@Composable
private fun NoiseControlSection(
    state: XmHeadphonesState,
    client: XmHeadphonesClient,
    scope: CoroutineScope,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_noise_cancelling),
            style = MaterialTheme.typography.titleMedium,
        )
        if (!state.supportsNoiseCancelling) {
            Text(stringResource(R.string.settings_noise_cancelling_unsupported))
            return
        }

        val current = state.noiseCancelling
        val ambient = current as? NoiseCancelling.Ambient
        // The headset's own report is the truth, but a slider that waited for it would fight the
        // thumb, so the level being dragged is held here and only sent when the rider lets go. One
        // write per drag rather than one per pixel, and the value shown is always the one they chose.
        var dragging by remember { mutableStateOf<Int?>(null) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(
                label = stringResource(R.string.settings_mode_off),
                selected = current is NoiseCancelling.Off,
                onClick = { scope.launch { client.setNoiseCancelling(NoiseCancelling.Off) } },
            )
            ModeChip(
                label = stringResource(R.string.settings_mode_cancelling),
                selected = current is NoiseCancelling.Cancelling,
                onClick = {
                    scope.launch { client.setNoiseCancelling(NoiseCancelling.Cancelling) }
                },
            )
            ModeChip(
                label = stringResource(R.string.settings_mode_ambient),
                selected = ambient != null,
                // Keeps whatever the rider set the level and the voice focus to.
                onClick = {
                    scope.launch {
                        client.setNoiseCancelling(
                            NoiseCancelling.Ambient(
                                // Where the rider left it, which neither of the other two modes
                                // carries.
                                ambient?.level ?: state.lastAmbientLevel ?: DEFAULT_AMBIENT_LEVEL,
                                ambient?.focusOnVoice ?: false,
                            ),
                        )
                    }
                },
            )
        }

        if (ambient != null) {
            val shown = dragging ?: ambient.level.steps
            Text(stringResource(R.string.settings_ambient_level, shown))
            Slider(
                value = shown.toFloat(),
                onValueChange = { dragging = it.toInt() },
                onValueChangeFinished = {
                    val steps = dragging ?: ambient.level.steps
                    dragging = null
                    scope.launch {
                        client.setNoiseCancelling(
                            NoiseCancelling.Ambient(
                                AmbientLevel.clamped(steps),
                                ambient.focusOnVoice,
                            ),
                        )
                    }
                },
                valueRange = AmbientLevel.MIN_STEPS.toFloat()..AmbientLevel.MAX_STEPS.toFloat(),
                steps = AmbientLevel.MAX_STEPS - AmbientLevel.MIN_STEPS - 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_focus_on_voice),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = ambient.focusOnVoice,
                    onCheckedChange = { voice ->
                        scope.launch {
                            client.setNoiseCancelling(NoiseCancelling.Ambient(ambient.level, voice))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EqualizerSection(
    state: XmHeadphonesState,
    client: XmHeadphonesClient,
    scope: CoroutineScope,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_equalizer), style = MaterialTheme.typography.titleMedium)
        if (!state.supportsEqualizer) {
            Text(stringResource(R.string.settings_equalizer_unsupported))
            return
        }
        Text(state.equalizerPreset?.label ?: stringResource(R.string.settings_unknown))
        // Twenty-one presets are modelled in the protocol and four are offered here. Which ones a
        // device has cannot be asked for, and a headset ignores a preset it does not know, so the
        // long tail would be a list of buttons that quietly do nothing on most headsets.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(EqualizerPreset.Off, EqualizerPreset.Rock, EqualizerPreset.Pop, EqualizerPreset.Jazz)
                .forEach { preset ->
                    ModeChip(
                        label = preset.label,
                        selected = state.equalizerPreset == preset,
                        onClick = { scope.launch { client.setEqualizerPreset(preset) } },
                    )
                }
        }
    }
}

@Composable
private fun Forgetting(client: XmHeadphonesClient) {
    var forgotten by remember { mutableStateOf(false) }
    Text(
        if (forgotten) {
            stringResource(R.string.settings_forgotten)
        } else {
            stringResource(R.string.settings_forget_help)
        },
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(
        onClick = {
            client.forget()
            forgotten = true
        },
    ) {
        Text(stringResource(R.string.settings_forget))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/** Where the ambient slider starts when the headset has not reported a level yet. */
private val DEFAULT_AMBIENT_LEVEL: AmbientLevel = AmbientLevel.clamped(10)
