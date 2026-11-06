package com.dragonemperor9480.yaedock.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dragonemperor9480.yaedock.protocol.MediaArt
import java.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.protocol.AppVolume
import com.dragonemperor9480.yaedock.protocol.AudioOutput
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.DisplayState
import com.dragonemperor9480.yaedock.protocol.Media
import com.dragonemperor9480.yaedock.protocol.MediaAction
import com.dragonemperor9480.yaedock.protocol.MediaControl
import com.dragonemperor9480.yaedock.protocol.PowerProfiles
import com.dragonemperor9480.yaedock.protocol.RunShortcut
import com.dragonemperor9480.yaedock.protocol.SetAppVolume
import com.dragonemperor9480.yaedock.protocol.SetBrightness
import com.dragonemperor9480.yaedock.protocol.SetMic
import com.dragonemperor9480.yaedock.protocol.SetOutput
import com.dragonemperor9480.yaedock.protocol.SetPowerProfile
import com.dragonemperor9480.yaedock.protocol.SetVolume
import com.dragonemperor9480.yaedock.protocol.Shortcut
import com.dragonemperor9480.yaedock.protocol.VolumeState

/**
 * What is playing on the PC, with its cover and previous, play/pause and next:
 * the buttons under the title when [stacked] (a narrow column), beside it
 * otherwise. With no [media] the card stays, saying nothing is playing, its
 * buttons off. [art] shows while it is the cover [media] names; until then,
 * and for players without covers, a music note.
 */
@Composable
fun MediaCard(media: Media?, art: MediaArt?, send: (ClientMessage) -> Unit, stacked: Boolean, modifier: Modifier = Modifier) {
    val cover = rememberCover(art?.takeIf { media?.art != null && it.id == media.art })
    val control = { action: MediaAction -> send(MediaControl(action, media?.player)) }
    val playing = media?.playing == true
    val canPlayPause = media?.canPlayPause == true
    val buttons = @Composable {
        RoundIconButton(R.drawable.ic_skip_previous, "Previous", media?.canPrevious == true) { control(MediaAction.PREVIOUS) }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (canPlayPause) YaeColors.accent else YaeColors.track)
                .clickable(enabled = canPlayPause, role = Role.Button, onClickLabel = if (playing) "Pause" else "Play") {
                    control(MediaAction.PLAY_PAUSE)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                if (playing) "Pause" else "Play",
                Modifier.size(20.dp),
                tint = if (canPlayPause) YaeColors.onAccent else YaeColors.muted,
            )
        }
        RoundIconButton(R.drawable.ic_skip_next, "Next", media?.canNext == true) { control(MediaAction.NEXT) }
    }
    DockCard(modifier.fillMaxWidth(), spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (media != null) YaeColors.accentSoft else YaeColors.raised),
                contentAlignment = Alignment.Center,
            ) {
                if (cover != null) {
                    Image(cover, "Cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(painterResource(R.drawable.ic_music), null, Modifier.size(20.dp), tint = if (media != null) YaeColors.accent else YaeColors.muted)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    media?.title?.ifEmpty { if (media.playing) "Playing" else "Paused" } ?: "No media playing",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (media != null) YaeColors.text else YaeColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                media?.let { Muted(listOf(it.artist, it.app).filter(String::isNotEmpty).joinToString(" · "), size = 12.sp) }
            }
            if (!stacked) buttons()
        }
        if (stacked) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) { buttons() }
        }
    }
}

/** [art] as an image, decoded once per cover; null if it is not a picture. */
@Composable
private fun rememberCover(art: MediaArt?): ImageBitmap? = remember(art?.id) {
    art?.let {
        runCatching {
            val bytes = Base64.getDecoder().decode(it.data)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun RoundIconButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), label, Modifier.size(20.dp), tint = if (enabled) YaeColors.text else YaeColors.track)
    }
}

/** One display's brightness, with its name when [label] is given (several displays). */
@Composable
fun BrightnessSlider(display: DisplayState, send: (ClientMessage) -> Unit, label: String? = null) {
    SyncedSlider(
        value = display.percent,
        max = 100,
        onChange = { send(SetBrightness(display.id, it)) },
        contentDescription = label ?: "Brightness",
        leading = {
            Icon(painterResource(R.drawable.ic_brightness), null, Modifier.size(20.dp), tint = YaeColors.muted)
            label?.let { Muted(it, Modifier.width(70.dp), size = 12.sp) }
        },
    )
}

/**
 * The output's volume. The speaker icon mutes it. Past 100% only when allowed,
 * or when the PC is already boosted (set from the desktop), so the real value is never hidden.
 */
@Composable
fun VolumeSlider(volume: VolumeState, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit) {
    SyncedSlider(
        value = volume.percent,
        max = if (allowVolumeBoost || volume.percent > 100) 150 else 100,
        onChange = { send(SetVolume(percent = it)) },
        contentDescription = "Volume",
        dimmed = volume.muted,
        leading = {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClickLabel = if (volume.muted) "Unmute" else "Mute") {
                        send(SetVolume(muted = !volume.muted))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(if (volume.muted) R.drawable.ic_volume_off else R.drawable.ic_volume),
                    if (volume.muted) "Unmute" else "Mute",
                    Modifier.size(20.dp),
                    tint = if (volume.muted) YaeColors.redText else YaeColors.muted,
                )
            }
        },
    )
}

/** Mutes the microphone; red while muted, the state worth noticing. */
@Composable
fun MicButton(mic: VolumeState, send: (ClientMessage) -> Unit) {
    SquareButton(
        painterResource(if (mic.muted) R.drawable.ic_mic_off else R.drawable.ic_mic),
        if (mic.muted) "Unmute microphone" else "Mute microphone",
        { send(SetMic(muted = !mic.muted)) },
        background = if (mic.muted) YaeColors.redSoft else YaeColors.raised,
        tint = if (mic.muted) YaeColors.redText else YaeColors.text,
    )
}

/** The output sound plays through, as a chip that opens the list of outputs. */
@Composable
fun OutputChip(outputs: List<AudioOutput>, send: (ClientMessage) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val current = outputs.firstOrNull { it.default }
    Box(modifier) {
        ChipButton(
            current?.let { it.port ?: outputLabel(it) } ?: "Output",
            { open = true },
            Modifier.fillMaxWidth(),
            icon = painterResource(R.drawable.ic_speaker),
            enabled = outputs.size > 1 || current == null,
        )
        OutputMenu(outputs, open, send) { open = false }
    }
}

@Composable
private fun OutputMenu(outputs: List<AudioOutput>, open: Boolean, send: (ClientMessage) -> Unit, onDismiss: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = YaeColors.raised) {
        for (output in outputs) {
            DropdownMenuItem(
                text = { Text(outputLabel(output)) },
                leadingIcon = {
                    if (output.default) Icon(Icons.Default.Check, "In use", tint = YaeColors.accent) else Spacer(Modifier.size(24.dp))
                },
                onClick = {
                    onDismiss()
                    if (!output.default) send(SetOutput(output.id))
                },
            )
        }
    }
}

/** The power profile as a chip that opens the list of profiles. */
@Composable
fun PowerProfileChip(power: PowerProfiles, send: (ClientMessage) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ChipButton(
            profileLabel(power.active),
            { open = true },
            Modifier.fillMaxWidth(),
            icon = painterResource(R.drawable.ic_bolt),
            iconTint = YaeColors.amber,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = YaeColors.raised) {
            for (profile in power.available) {
                DropdownMenuItem(
                    text = { Text(profileLabel(profile)) },
                    leadingIcon = { Icon(painterResource(profileIcon(profile)), null, Modifier.size(20.dp)) },
                    trailingIcon = { if (profile == power.active) Icon(Icons.Default.Check, "In use", tint = YaeColors.accent) },
                    onClick = {
                        open = false
                        if (profile != power.active) send(SetPowerProfile(profile))
                    },
                )
            }
        }
    }
}

/** The outputs side by side, the one in use selected. */
@Composable
fun OutputButtons(outputs: List<AudioOutput>, send: (ClientMessage) -> Unit) {
    // More than three would not fit side by side; then a list opens instead.
    if (outputs.size > 3) {
        OutputChip(outputs, send, Modifier.fillMaxWidth())
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (output in outputs) {
            ChipButton(
                output.port ?: outputLabel(output),
                { if (!output.default) send(SetOutput(output.id)) },
                Modifier.weight(1f),
                selected = output.default,
            )
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

/** `power-saver` → `Saver`: short enough for three side by side. */
fun profileLabel(profile: String): String = when (profile) {
    "power-saver" -> "Saver"
    "balanced" -> "Balanced"
    "performance" -> "Performance"
    else -> profile.replace('-', ' ').replaceFirstChar { it.uppercase() }
}

fun profileIcon(profile: String): Int = when (profile) {
    "power-saver" -> R.drawable.ic_leaf
    "performance" -> R.drawable.ic_bolt
    else -> R.drawable.ic_balance
}

/** The PC's shortcut buttons; tapping one runs its command on the PC. */
@Composable
fun ShortcutButtons(shortcuts: List<Shortcut>, send: (ClientMessage) -> Unit, columns: Int) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in shortcuts.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (shortcut in row) {
                    ChipButton(shortcut.name, {
                        send(RunShortcut(shortcut.id))
                        // The PC only answers if it could not start it.
                        Toast.makeText(context, "Starting ${shortcut.name}", Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One program's volume: its initial, its name, a slider, and mute by tapping the name. */
@Composable
fun AppVolumeRow(app: AppVolume, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit) {
    SyncedSlider(
        value = app.percent,
        max = if (allowVolumeBoost || app.percent > 100) 150 else 100,
        onChange = { send(SetAppVolume(app.id, percent = it)) },
        contentDescription = "${app.name} volume",
        dimmed = app.muted,
        leading = {
            Row(
                Modifier.width(104.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = if (app.muted) "Unmute ${app.name}" else "Mute ${app.name}") {
                        send(SetAppVolume(app.id, muted = !app.muted))
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(YaeColors.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    if (app.muted) {
                        Icon(painterResource(R.drawable.ic_volume_off), null, Modifier.size(16.dp), tint = YaeColors.redText)
                    } else {
                        Text(app.name.take(1).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = YaeColors.accent)
                    }
                }
                Text(
                    app.name,
                    fontSize = 13.sp,
                    color = if (app.muted) YaeColors.muted else YaeColors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}
