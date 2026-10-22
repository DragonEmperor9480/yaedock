package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.protocol.AppVolume
import com.dragonemperor9480.yaedock.protocol.AudioOutput
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Media
import com.dragonemperor9480.yaedock.protocol.MediaAction
import com.dragonemperor9480.yaedock.protocol.MediaControl
import com.dragonemperor9480.yaedock.protocol.PowerProfiles
import com.dragonemperor9480.yaedock.protocol.SetAppVolume
import com.dragonemperor9480.yaedock.protocol.SetOutput
import com.dragonemperor9480.yaedock.protocol.SetPowerProfile

/** What is playing on the PC, with previous, play/pause and next. */
@Composable
fun MediaCard(media: Media, send: (ClientMessage) -> Unit, modifier: Modifier = Modifier) {
    val control = { action: MediaAction -> send(MediaControl(action, media.player)) }
    Card(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_music), null, Modifier.size(16.dp), tint = MetricColors.game)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        media.app,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    media.title.ifEmpty { if (media.playing) "Playing" else "Paused" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (media.artist.isNotEmpty()) {
                    Text(
                        media.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = { control(MediaAction.PREVIOUS) }, enabled = media.canPrevious) {
                Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous")
            }
            FilledIconButton(onClick = { control(MediaAction.PLAY_PAUSE) }, enabled = media.canPlayPause) {
                Icon(
                    painterResource(if (media.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (media.playing) "Pause" else "Play",
                )
            }
            IconButton(onClick = { control(MediaAction.NEXT) }, enabled = media.canNext) {
                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next")
            }
        }
    }
}

/** Which output sound plays through, and a list to choose another. */
@Composable
fun OutputPicker(outputs: List<AudioOutput>, send: (ClientMessage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = outputs.firstOrNull { it.default }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(enabled = outputs.size > 1) { open = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_speaker), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                current?.let(::outputLabel) ?: "Choose an output",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (outputs.size > 1) Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose the audio output")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (output in outputs) {
                DropdownMenuItem(
                    text = { Text(outputLabel(output)) },
                    leadingIcon = {
                        if (output.default) Icon(Icons.Default.Check, contentDescription = "In use") else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        if (!output.default) send(SetOutput(output.id))
                    },
                )
            }
        }
    }
}

/**
 * "Built-in Audio · Headphones": the device and the port, without the channel
 * layout ALSA puts in device names ("Analog Stereo"), which tells most people nothing.
 */
fun outputLabel(output: AudioOutput): String {
    val name = Regex(" (Analog|Digital) (Stereo|Surround[^(]*|Mono)").replace(output.name, "")
    return listOfNotNull(name, output.port).joinToString(" · ")
}

/** The PC's power profiles side by side, the active one selected. */
@Composable
fun PowerProfilePicker(power: PowerProfiles, send: (ClientMessage) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_bolt), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Power mode", style = MaterialTheme.typography.titleSmall)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            power.available.forEachIndexed { i, profile ->
                SegmentedButton(
                    selected = profile == power.active,
                    onClick = { if (profile != power.active) send(SetPowerProfile(profile)) },
                    shape = SegmentedButtonDefaults.itemShape(i, power.available.size),
                    // Room for the label matters more than a check mark.
                    icon = {},
                ) {
                    // Shrinks to fit the narrow column of the landscape dock.
                    Text(
                        profileLabel(profile),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 9.sp,
                            maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                        ),
                    )
                }
            }
        }
    }
}

/** `power-saver` → `Saver`: short enough for three side by side. */
fun profileLabel(profile: String): String = when (profile) {
    "power-saver" -> "Saver"
    "balanced" -> "Balanced"
    "performance" -> "Performance"
    else -> profile.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

/** A volume slider and mute button for each program playing sound. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerSheet(apps: List<AppVolume>, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (apps.isEmpty()) {
                Text(
                    "No app is playing sound right now.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (app in apps) {
                // Ids are reused only by new streams, so a key keeps each slider's drag state with its app.
                key(app.id) {
                    SyncedSlider(
                        icon = R.drawable.ic_volume,
                        label = app.name,
                        value = app.percent,
                        max = if (allowVolumeBoost || app.percent > 100) 150 else 100,
                        onChange = { send(SetAppVolume(app.id, percent = it)) },
                        trailing = {
                            FilledTonalIconToggleButton(
                                checked = app.muted,
                                onCheckedChange = { send(SetAppVolume(app.id, muted = it)) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painterResource(if (app.muted) R.drawable.ic_volume_off else R.drawable.ic_volume),
                                    contentDescription = if (app.muted) "Unmute ${app.name}" else "Mute ${app.name}",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
