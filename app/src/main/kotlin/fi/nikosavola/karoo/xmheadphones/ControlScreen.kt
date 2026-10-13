package fi.nikosavola.karoo.xmheadphones

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import fi.nikosavola.xmheadphones.client.HeadphonesLink
import fi.nikosavola.xmheadphones.client.XmHeadphonesState
import fi.nikosavola.xmheadphones.protocol.AmbientLevel
import fi.nikosavola.xmheadphones.protocol.BatteryReading
import fi.nikosavola.xmheadphones.protocol.BatteryShape
import fi.nikosavola.xmheadphones.protocol.EqualizerPreset
import fi.nikosavola.xmheadphones.protocol.NcAsmDialect
import fi.nikosavola.xmheadphones.protocol.NoiseCancelling
import fi.nikosavola.xmheadphones.protocol.ProtocolTable
import fi.nikosavola.xmheadphones.protocol.ProtocolVersion

/**
 * The headset controls.
 *
 * Karoo's settings cannot hold a slider or preset list, so everything Sound Connect offers lives
 * here. Only fields a rider glances at mid-ride go on a page.
 *
 * Hoisted state keeps the screen a pure function of [XmHeadphonesState], so screenshot tests can
 * render any state without a headset.
 */
@Composable
internal fun ControlScreen(
    state: XmHeadphonesState,
    setNoiseCancelling: (NoiseCancelling) -> Unit,
    setEqualizerPreset: (EqualizerPreset) -> Unit,
    forget: () -> Unit,
    askForPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Connection(state, onAskForPermissions = askForPermissions)

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            NoiseControlSection(state, setNoiseCancelling)

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            EqualizerSection(state, setEqualizerPreset)

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Forgetting(forget)
        }
    }
}

/** The heading of a section, in the accent so the sections read as one set of controls. */
@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Connection(state: XmHeadphonesState, onAskForPermissions: () -> Unit) {
    var detailsAsked by remember { mutableStateOf(false) }
    // Hung off the link rather than folded into it, so a headset that drops puts the panel away by
    // itself. Closing it on the way out of composition would be a write during composition, and the
    // panel would still be open the moment the headset came back.
    val live = state.link == HeadphonesLink.Connected
    val showDetails = detailsAsked && live

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(stringResource(R.string.settings_connection), Modifier.weight(1f))
            if (live) {
                // On the heading rather than beside the name. The name is a model and a firmware
                // version, and on a Karoo it needs very nearly the whole row: an affordance next to
                // it takes enough to wrap the name onto a second line.
                Text(
                    stringResource(
                        if (showDetails) R.string.settings_details_hide
                        else R.string.settings_details_show
                    ),
                    modifier =
                        Modifier.clickable(role = Role.Button) { detailsAsked = !detailsAsked },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(
            // The name is the whole target, which is the one thing on this screen a rider has to hit
            // exactly. Inert with no link, since there is no headset to describe. The role is what
            // puts it in a screen reader's list of buttons rather than leaving it as plain text that
            // happens to do something.
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(enabled = live, role = Role.Button) { detailsAsked = !detailsAsked },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinkDot(state.link)
            Text(
                when (state.link) {
                    HeadphonesLink.Connected -> state.device.toString()
                    HeadphonesLink.Searching -> stringResource(R.string.state_searching)
                    HeadphonesLink.Connecting -> stringResource(R.string.settings_connecting)
                    HeadphonesLink.NoPermission -> stringResource(R.string.settings_no_permission)
                    HeadphonesLink.BluetoothOff -> stringResource(R.string.settings_bluetooth_off)
                    HeadphonesLink.NoPairedHeadset -> stringResource(R.string.state_no_headset)
                    HeadphonesLink.Idle -> stringResource(R.string.settings_idle)
                },
                // Unweighted, and a size down from the body default. A weighted child is measured
                // against its share of the row rather than the whole of it, and a model name and
                // firmware version is within a character or two of the Karoo's 256dp either way.
                // At the body default it wraps there, which breaks the line a rider reads first
                // across two.
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // The one state a rider has to fix somewhere else. Pairing is Karoo's own settings screen, and
        // a screen that only ever says it is waiting leaves them with nothing to try.
        if (state.link == HeadphonesLink.NoPairedHeadset) {
            Text(
                stringResource(R.string.settings_pair_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The headset's own words for whatever went wrong, which is the difference between "table 1
        // disabled" and a channel that will not open. The client keeps it for this line and nothing
        // else, so without it a refused headset is a screen that only says it is waiting.
        if (!live) {
            state.lastError?.let { error ->
                Text(
                    stringResource(R.string.settings_last_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (showDetails) HeadsetDetails(state)
        state.battery?.let { Battery(it) }
        if (state.link == HeadphonesLink.NoPermission) {
            Button(onClick = onAskForPermissions) {
                Text(stringResource(R.string.settings_grant_permission))
            }
        }
    }
}

/**
 * What the handshake found out, for when a control is not doing what it should.
 *
 * Everything here came from the headset. It is the difference between "the mode did not change" and
 * knowing which layout was written, which is the first question when one model misbehaves and
 * another does not.
 */
@Composable
private fun HeadsetDetails(state: XmHeadphonesState) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val capabilities = state.capabilities
        DetailRow(stringResource(R.string.settings_details_model), state.device.modelName)
        DetailRow(stringResource(R.string.settings_details_firmware), state.device.firmwareVersion)
        DetailRow(stringResource(R.string.settings_details_address), state.address)
        DetailRow(
            stringResource(R.string.settings_details_protocol),
            capabilities?.let {
                stringResource(
                    when (it.version) {
                        ProtocolVersion.V1 -> R.string.settings_protocol_v1
                        ProtocolVersion.V2 -> R.string.settings_protocol_v2
                    }
                )
            },
        )
        DetailRow(
            stringResource(R.string.settings_details_tables),
            capabilities?.tables?.takeIf { it.isNotEmpty() }?.let { tables ->
                ProtocolTable.entries.filter { it in tables }
                    .joinToString(", ") { "${it.ordinal + 1}" }
            }
                ?: capabilities?.let { stringResource(R.string.settings_details_none) },
        )
        DetailRow(
            stringResource(R.string.settings_details_battery),
            capabilities?.batteryShape?.let {
                stringResource(
                    when (it) {
                        BatteryShape.Single -> R.string.settings_battery_single
                        BatteryShape.LeftRight -> R.string.settings_battery_left_right
                    }
                )
            },
        )
        DetailRow(
            stringResource(R.string.settings_details_noise_control),
            capabilities?.noiseCancelling?.let {
                stringResource(
                    when (it) {
                        NcAsmDialect.V1 -> R.string.settings_nc_dialect_v1
                        NcAsmDialect.V2 -> R.string.settings_nc_dialect_v2
                        NcAsmDialect.V2NoiseAdaptive -> R.string.settings_nc_dialect_v2_adaptive
                    }
                )
            },
        )
        DetailRow(
            stringResource(R.string.settings_details_equalizer),
            capabilities?.let {
                stringResource(
                    if (it.supportsEqualizerPreset) R.string.settings_details_yes
                    else R.string.settings_details_no
                )
            },
        )
        // No row for the last error. The panel only opens on a link that is up, and a link that came
        // up is one that cleared the error on the way. The field is for a screen that can be looked
        // at while nothing is connected, which this one is not.
    }
}

/** One label and its value. Nothing to say means no row, rather than a line reading "null". */
@Composable
private fun DetailRow(label: String, value: String?) {
    // Empty as well as null. A headset that answers with no characters has said nothing, and a label
    // beside a blank is a worse answer than no row at all.
    if (value.isNullOrEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // The label takes what its own text needs and the value gets the rest, which is the way
        // round that survives both ends: a value left to take its own width takes the whole row and
        // measures the label down to nothing beside it, and an even split wraps the address, the
        // longest value a headset produces, even at the width of the wider test render.
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Connected, looking, or off, in the one thing a rider can read at a glance from a saddle.
 *
 * Deliberately without semantics. The colour is the whole of what it says, and a screen reader
 * already has the words beside it, which name the state except when the link is up, where the
 * headset answering with its own name says it.
 */
@Composable
private fun LinkDot(link: HeadphonesLink) {
    val colour =
        when (link) {
            HeadphonesLink.Connected -> MaterialTheme.colorScheme.primary
            HeadphonesLink.Searching,
            HeadphonesLink.Connecting -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.error
        }
    Box(
        modifier =
            Modifier.size(10.dp).background(colour, CircleShape),
    )
}

/**
 * The battery, as a gauge with the number beside it.
 *
 * The fill is what a rider reads without stopping to parse digits, and it turns red as the headset
 * runs out, which is the only moment the number matters. Cardinality comes from the reading rather
 * than the state, since earbuds answer per bud and the lower one is what a ride shows.
 */
@Composable
private fun Battery(reading: BatteryReading) {
    val status =
        when (reading) {
            is BatteryReading.Single -> reading.status
            is BatteryReading.LeftRight -> reading.lowest
        }
    val colour =
        if (status.percent <= LOW_BATTERY_PERCENT) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.testTag(BATTERY_GAUGE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier.size(width = 24.dp, height = 12.dp)
                            .border(1.5.dp, colour, RoundedCornerShape(3.dp))
                            .padding(2.dp),
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxHeight()
                                .fillMaxWidth(status.percent / 100f)
                                .background(colour, RoundedCornerShape(1.dp)),
                    )
                }
                // The terminal every battery is drawn with, which is what makes it read as one.
                Box(
                    modifier =
                        Modifier.size(width = 3.dp, height = 6.dp)
                            .background(colour, RoundedCornerShape(topEnd = 1.dp, bottomEnd = 1.dp)),
                )
            }
            Text(
                stringResource(R.string.settings_battery_percent, status.percent),
                style = MaterialTheme.typography.titleMedium,
                color = colour,
            )
            if (status.charging) {
                Text(
                    stringResource(R.string.settings_battery_charging),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            stringResource(R.string.settings_battery),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoiseControlSection(
    state: XmHeadphonesState,
    setNoiseCancelling: (NoiseCancelling) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(stringResource(R.string.settings_noise_cancelling))
        val blocked =
            state.notReadyText(
                supported = state.supportsNoiseCancelling,
                unsupported = stringResource(R.string.settings_noise_cancelling_unsupported),
            )
        if (blocked != null) {
            Text(blocked)
            return
        }
        // Connected and able, but the handshake never reported a mode. The chips still work. Gated
        // on the link: with no permission or the radio off nothing is being waited for, and this
        // would sit under the message saying so.
        if (state.link == HeadphonesLink.Connected && state.noiseCancelling == null) {
            Text(stringResource(R.string.settings_noise_report_pending))
        }

        // Inert without a link. A press would otherwise set the pending change and leave it set,
        // since nothing is coming back to clear it, so the screen would claim a mode no headset took.
        val live = state.link == HeadphonesLink.Connected

        // The headset's report is the truth but arrives a round trip late. Until it does, show
        // the rider's own change and build the next change on it. Otherwise dragging to 14 and
        // toggling focus sends the level last reported. Keyed on the mode alone: the battery poll
        // replaces the state too, and clearing on that would drop a change still in flight.
        var intended by remember { mutableStateOf<NoiseCancelling?>(null) }
        LaunchedEffect(state.noiseCancelling) { intended = null }
        val shown = intended ?: state.noiseCancelling
        val ambient = shown as? NoiseCancelling.Ambient
        val base = if (intended == null) state else state.copy(noiseCancelling = shown)

        // Send the dragged level only on release. One write per drag, not one per pixel.
        var dragging by remember { mutableStateOf<Int?>(null) }

        fun apply(next: NoiseCancelling) {
            intended = next
            setNoiseCancelling(next)
        }

        // The three states are one choice, so they are one control rather than three buttons. The
        // middle label is short on purpose: the header above spells it out, and the full name does
        // not fit a third of a Karoo's width without wrapping onto a second line.
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ModeSegment(
                label = stringResource(R.string.settings_mode_off),
                selected = shown is NoiseCancelling.Off,
                index = 0,
                enabled = live,
                onClick = { apply(NoiseCancelling.Off) },
            )
            ModeSegment(
                label = stringResource(R.string.settings_mode_nc),
                selected = shown is NoiseCancelling.Cancelling,
                index = 1,
                enabled = live,
                onClick = { apply(NoiseCancelling.Cancelling) },
            )
            ModeSegment(
                label = stringResource(R.string.settings_mode_ambient),
                selected = ambient != null,
                index = 2,
                enabled = live,
                onClick = { apply(NoiseCancelling.Ambient(ambientLevelOf(base), focusOnVoiceOf(base))) },
            )
        }

        if (ambient != null) {
            val level = dragging ?: ambient.level.steps
            Text(
                stringResource(R.string.settings_ambient_level, level),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                enabled = live,
                value = level.toFloat(),
                onValueChange = { dragging = it.toInt() },
                onValueChangeFinished = {
                    // The step the rider stopped on, not the previous frame's.
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
                    enabled = live,
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
        SectionHeading(stringResource(R.string.settings_equalizer))
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
        // The protocol models twenty-one presets. A device cannot be asked which it has, and it
        // silently ignores unknown ones, so the rest would do nothing on most headsets.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(EqualizerPreset.Off, EqualizerPreset.Rock, EqualizerPreset.Pop, EqualizerPreset.Jazz)
                .forEach { preset ->
                    ModeChip(
                        label = preset.label,
                        selected = state.equalizerPreset == preset,
                        enabled = state.link == HeadphonesLink.Connected,
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
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}

/**
 * One of the three noise control modes.
 *
 * No check mark: the filled shape already says which one is on, and the mark eats a third of a
 * segment this narrow.
 */
@Composable
private fun SingleChoiceSegmentedButtonRowScope.ModeSegment(
    label: String,
    selected: Boolean,
    index: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    SegmentedButton(
        shape = SegmentedButtonDefaults.itemShape(index = index, count = MODE_COUNT),
        onClick = onClick,
        selected = selected,
        enabled = enabled,
        icon = {},
        contentPadding = PaddingValues(horizontal = 8.dp),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
    )
}

/**
 * Why this section is empty, or null when the controls are offered.
 *
 * Only a search means a headset is actually being waited for. With permission or Bluetooth off
 * nothing is looking, and a waiting message would blame the headset rather than either one.
 */
@Composable
private fun XmHeadphonesState.notReadyText(supported: Boolean, unsupported: String): String? =
    when {
        link == HeadphonesLink.Connected && supported -> null
        // Connected, so what is missing is the headset's answer.
        link == HeadphonesLink.Connected -> unsupported
        link == HeadphonesLink.Searching || link == HeadphonesLink.Connecting ->
            stringResource(R.string.settings_waiting_for_headset)
        else -> null
    }

/** How many segments the noise control row is split into. */
private const val MODE_COUNT = 3

/** Below this the gauge turns red, which is roughly the point a rider should start thinking about it. */
private const val LOW_BATTERY_PERCENT = 20

/** On the gauge, so a test can read the colour the level put it in. */
internal const val BATTERY_GAUGE = "battery-gauge"


/** Where the slider starts when no one has named a level. */
private val DEFAULT_AMBIENT_LEVEL: AmbientLevel = AmbientLevel.clamped(10)

/** The level to come back to. Ambient's own when on, else the rider's last. */
private fun ambientLevelOf(state: XmHeadphonesState): AmbientLevel =
    (state.noiseCancelling as? NoiseCancelling.Ambient)?.level
        ?: state.lastAmbientLevel
        ?: DEFAULT_AMBIENT_LEVEL

private fun focusOnVoiceOf(state: XmHeadphonesState): Boolean =
    (state.noiseCancelling as? NoiseCancelling.Ambient)?.focusOnVoice
        ?: state.lastFocusOnVoice
        ?: false
