package fi.nikosavola.karoo.xmheadphones

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling

/**
 * The headset controls.
 *
 * Karoo's own settings cannot hold a slider or a list of presets, so everything the Sound Connect
 * app offers lives here and only the fields a rider glances at during a ride go on a page.
 *
 * State is hoisted rather than read from the client inside: the screen is then a pure function of a
 * [XmHeadphonesState], which is what lets the screenshot tests render every state deliberately
 * instead of waiting for a headset to reach one.
 */
@Composable
internal fun ControlScreen(
    state: XmHeadphonesState,
    setNoiseCancelling: (NoiseCancelling) -> Unit,
    setEqualizerPreset: (EqualizerPreset) -> Unit,
    forget: () -> Unit,
    askForPermissions: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
            Connection(state, onAskForPermissions = askForPermissions)

            HorizontalDivider()
            NoiseControlSection(state, setNoiseCancelling)

            HorizontalDivider()
            EqualizerSection(state, setEqualizerPreset)

            HorizontalDivider()
            Forgetting(forget)
        }
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
    setNoiseCancelling: (NoiseCancelling) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.settings_noise_cancelling),
            style = MaterialTheme.typography.titleMedium,
        )
        val blocked =
            state.notReadyText(
                supported = state.supportsNoiseCancelling,
                unsupported = stringResource(R.string.settings_noise_cancelling_unsupported),
            )
        if (blocked != null) {
            Text(blocked)
            return
        }
        // Connected and able, but the mode it reported at the handshake never arrived. The chips
        // still work, so they stay; this only says why none of them reads as selected.
        if (state.noiseCancelling == null) {
            Text(stringResource(R.string.settings_noise_report_pending))
        }

        // The headset's own report is the truth, but it arrives a round trip late. Until it does,
        // the rider's own change is what the screen shows and what the next change builds on:
        // without it, dragging the level to 14 and then toggling focus on voice sends the level the
        // headset last reported, undoing the drag. Any report clears it, including one that refuses
        // what was asked, so it cannot outlive the headset's answer.
        var intended by remember { mutableStateOf<NoiseCancelling?>(null) }
        LaunchedEffect(state) { intended = null }
        val shown = intended ?: state.noiseCancelling
        val ambient = shown as? NoiseCancelling.Ambient
        val base = if (intended == null) state else state.copy(noiseCancelling = shown)

        // The level being dragged is held here and only sent when the rider lets go: one write per
        // drag rather than one per pixel, and the value shown is the one they chose.
        var dragging by remember { mutableStateOf<Int?>(null) }

        fun apply(next: NoiseCancelling) {
            intended = next
            setNoiseCancelling(next)
        }

        // FlowRow, not Row: three chips do not fit across the Karoo 3's 274dp, and a Row squeezes
        // the last one instead of moving it down, which wraps its label to one letter per line.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip(
                label = stringResource(R.string.settings_mode_off),
                selected = shown is NoiseCancelling.Off,
                onClick = { apply(NoiseCancelling.Off) },
            )
            ModeChip(
                label = stringResource(R.string.settings_mode_cancelling),
                selected = shown is NoiseCancelling.Cancelling,
                onClick = { apply(NoiseCancelling.Cancelling) },
            )
            ModeChip(
                label = stringResource(R.string.settings_mode_ambient),
                selected = ambient != null,
                onClick = { apply(NoiseCancelling.Ambient(ambientLevelOf(base), focusOnVoiceOf(base))) },
            )
        }

        if (ambient != null) {
            val level = dragging ?: ambient.level.steps
            Text(stringResource(R.string.settings_ambient_level, level))
            Slider(
                value = level.toFloat(),
                onValueChange = { dragging = it.toInt() },
                onValueChangeFinished = {
                    // The step the rider stopped on, not the one from the frame before it.
                    val steps = dragging ?: ambient.level.steps
                    dragging = null
                    apply(NoiseCancelling.Ambient(AmbientLevel.clamped(steps), focusOnVoiceOf(base)))
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
                        apply(NoiseCancelling.Ambient(ambientLevelOf(base), voice))
                    },
                )
            }
        }
    }
}

@Composable
private fun EqualizerSection(
    state: XmHeadphonesState,
    setEqualizerPreset: (EqualizerPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_equalizer), style = MaterialTheme.typography.titleMedium)
        val blocked =
            state.notReadyText(
                supported = state.supportsEqualizer,
                unsupported = stringResource(R.string.settings_equalizer_unsupported),
            )
        if (blocked != null) {
            Text(blocked)
            return
        }
        Text(state.equalizerPreset?.label ?: stringResource(R.string.settings_unknown))
        // Twenty-one presets are modelled in the protocol and four are offered here. Which ones a
        // device has cannot be asked for, and a headset ignores a preset it does not know, so the
        // long tail would be a list of buttons that quietly do nothing on most headsets.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(EqualizerPreset.Off, EqualizerPreset.Rock, EqualizerPreset.Pop, EqualizerPreset.Jazz)
                .forEach { preset ->
                    ModeChip(
                        label = preset.label,
                        selected = state.equalizerPreset == preset,
                        onClick = { setEqualizerPreset(preset) },
                    )
                }
        }
    }
}

@Composable
private fun Forgetting(forget: () -> Unit) {
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
            forget()
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

/**
 * Why this section has no controls to show, or null when the connected headset offers them.
 *
 * Only a search says a headset is being waited for. Without the permission or with Bluetooth off
 * nothing is looking for one, so the section says nothing at all and leaves the connection row to
 * name the blocker; "waiting for a headset" there would send the rider looking for a headset to
 * switch on rather than the thing that is switched off.
 */
@Composable
private fun XmHeadphonesState.notReadyText(supported: Boolean, unsupported: String): String? =
    when {
        link == HeadphonesLink.Connected && supported -> null
        // Connected, so what is missing is its own answer rather than a connection.
        link == HeadphonesLink.Connected -> unsupported
        link == HeadphonesLink.Searching || link == HeadphonesLink.Connecting ->
            stringResource(R.string.settings_waiting_for_headset)
        else -> null
    }

/** Where the ambient slider starts when neither the headset nor the rider has named a level. */
private val DEFAULT_AMBIENT_LEVEL: AmbientLevel = AmbientLevel.clamped(10)

/** The level to come back to: the one ambient mode is on, or the last one the rider had. */
private fun ambientLevelOf(state: XmHeadphonesState): AmbientLevel =
    (state.noiseCancelling as? NoiseCancelling.Ambient)?.level
        ?: state.lastAmbientLevel
        ?: DEFAULT_AMBIENT_LEVEL

private fun focusOnVoiceOf(state: XmHeadphonesState): Boolean =
    (state.noiseCancelling as? NoiseCancelling.Ambient)?.focusOnVoice
        ?: state.lastFocusOnVoice
        ?: false
