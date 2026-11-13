package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.PowerProfiles
import com.dragonemperor9480.yaedock.protocol.SetOverlay
import com.dragonemperor9480.yaedock.protocol.SetPowerProfile
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.SystemAction

/**
 * Everything that can be changed on the PC: sound (outputs, volume, each
 * app), brightness, the power profile, MangoHud's overlay, shortcut buttons,
 * and locking or powering the PC.
 */
@Composable
fun ControlsScreen(
    pcName: String,
    status: Status,
    state: State?,
    wide: Boolean,
    allowVolumeBoost: Boolean,
    send: (ClientMessage) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    onSettings: () -> Unit,
    onMirror: () -> Unit,
    phoneMic: Boolean,
    onPhoneMic: (Boolean) -> Unit,
) {
    val welcome = (status as? Status.Connected)?.welcome
    val header = @Composable {
        ScreenHeader("Controls", pcName) {
            if (welcome?.phoneMic == true) {
                // Mint while lent, the state worth noticing: the PC is listening.
                SquareButton(
                    painterResource(R.drawable.ic_mic),
                    if (phoneMic) "Stop using this phone as the PC's microphone" else "Use this phone as the PC's microphone",
                    { onPhoneMic(!phoneMic) },
                    background = if (phoneMic) YaeColors.accentSoft else YaeColors.card,
                    tint = if (phoneMic) YaeColors.accent else YaeColors.text,
                )
            }
            if (welcome?.screen == true) SquareButton(painterResource(R.drawable.ic_laptop), "Mirror the PC's screen", onMirror)
            // The bar has a settings button in landscape only.
            if (!wide) SquareButton(settingsIcon(), "Settings", onSettings)
        }
    }
    if (welcome == null || state == null) {
        Column(Modifier.fillMaxSize()) {
            header()
            StatusPanel(pcName, status, onRetry, onPairAgain)
        }
        return
    }
    val actions = welcome.systemActions
    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(0.36f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoundCard(state, allowVolumeBoost, send, Modifier.weight(1f).fillMaxWidth(), scroll = true)
                    state.overlay?.let { OverlayCard(it, send, Modifier.fillMaxWidth()) }
                }
                Column(Modifier.weight(0.31f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.displays.isNotEmpty()) BrightnessCard(state, send, Modifier.fillMaxWidth())
                    state.power?.let { ProfileCard(it, send, vertical = true, Modifier.weight(1f).fillMaxWidth()) }
                }
                Column(Modifier.weight(0.33f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShortcutsCard(state, send, columns = 2, Modifier.fillMaxWidth())
                    if (actions.isNotEmpty()) PowerCard(pcName, actions, send, columns = 2, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            SoundCard(state, allowVolumeBoost, send, Modifier.fillMaxWidth(), scroll = false)
            if (state.displays.isNotEmpty() || state.power != null) {
                DockCard(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    CardHeader("Display & power")
                    Brightness(state, send)
                    state.power?.let { ProfileButtons(it, send, vertical = false) }
                }
            }
            state.overlay?.let { OverlayCard(it, send, Modifier.fillMaxWidth()) }
            ShortcutsCard(state, send, columns = 4, Modifier.fillMaxWidth())
            if (actions.isNotEmpty()) PowerCard(pcName, actions, send, columns = 4, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SoundCard(state: State, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit, modifier: Modifier, scroll: Boolean) {
    DockCard(modifier, spacing = 8.dp) {
        CardHeader("Sound")
        if (state.outputs.isNotEmpty()) OutputButtons(state.outputs, send)
        state.volume?.let { volume ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { VolumeSlider(volume, allowVolumeBoost, send) }
                state.mic?.let { MicButton(it, send) }
            }
        }
        if (state.volume == null && state.outputs.isEmpty()) Muted("This PC has no volume control.", size = 13.sp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(YaeColors.track))
        Column(
            if (scroll) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (state.apps.isEmpty()) Muted("No app is playing sound right now.", Modifier.padding(vertical = 8.dp), size = 12.sp)
            for (app in state.apps) {
                // Ids are reused only by new streams, so a key keeps each slider's drag state with its app.
                key(app.id) { AppVolumeRow(app, allowVolumeBoost, send) }
            }
        }
    }
}

@Composable
private fun BrightnessCard(state: State, send: (ClientMessage) -> Unit, modifier: Modifier) {
    DockCard(modifier, spacing = 2.dp) {
        CardHeader("Brightness")
        Brightness(state, send)
    }
}

/** A slider for each display, named when there is more than one. */
@Composable
private fun Brightness(state: State, send: (ClientMessage) -> Unit) {
    for (display in state.displays) {
        key(display.id) { BrightnessSlider(display, send, label = display.name.takeIf { state.displays.size > 1 }) }
    }
}

@Composable
private fun ProfileCard(power: PowerProfiles, send: (ClientMessage) -> Unit, vertical: Boolean, modifier: Modifier) {
    DockCard(modifier, spacing = 8.dp) {
        CardHeader("Power profile")
        ProfileButtons(power, send, vertical, Modifier.weight(1f))
    }
}

/** The profiles as buttons; the active one in amber. */
@Composable
private fun ProfileButtons(power: PowerProfiles, send: (ClientMessage) -> Unit, vertical: Boolean, modifier: Modifier = Modifier) {
    val button = @Composable { profile: String, buttonModifier: Modifier ->
        val active = profile == power.active
        Row(
            buttonModifier
                .clip(ChipShape)
                .background(if (active) YaeColors.amberSoft else YaeColors.raised)
                .clickable(role = Role.RadioButton) { if (!active) send(SetPowerProfile(profile)) }
                .padding(horizontal = if (vertical) 12.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, if (vertical) Alignment.Start else Alignment.CenterHorizontally),
        ) {
            // Side by side there is room for the label only.
            if (vertical) {
                Icon(
                    painterResource(profileIcon(profile)),
                    null,
                    Modifier.size(18.dp),
                    tint = if (active) YaeColors.amber else YaeColors.muted,
                )
            }
            Text(
                profileLabel(profile),
                fontSize = if (vertical) 13.sp else 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) YaeColors.amberText else YaeColors.text,
                maxLines = 1,
            )
        }
    }
    if (vertical) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (profile in power.available) button(profile, Modifier.weight(1f).fillMaxWidth())
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (profile in power.available) button(profile, Modifier.weight(1f).height(44.dp))
        }
    }
}

/** Shows or hides MangoHud's overlay on the PC, even in a running game. */
@Composable
private fun OverlayCard(visible: Boolean, send: (ClientMessage) -> Unit, modifier: Modifier) {
    DockCard(modifier, spacing = 0.dp) {
        Row(
            Modifier.fillMaxWidth().clip(ChipShape).clickable(role = Role.Switch) { send(SetOverlay(!visible)) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("MangoHud overlay", fontSize = 14.sp)
                Muted(if (visible) "Showing on the PC" else "Hidden; FPS still comes here")
            }
            DockSwitch(visible, { send(SetOverlay(it)) }, "MangoHud overlay on the PC")
        }
    }
}

@Composable
private fun ShortcutsCard(state: State, send: (ClientMessage) -> Unit, columns: Int, modifier: Modifier) {
    DockCard(modifier, spacing = 8.dp) {
        CardHeader("Shortcuts")
        if (state.shortcuts.isEmpty()) {
            Muted("Add buttons on the PC with yaedock shortcuts add NAME COMMAND.", size = 12.sp, maxLines = 3)
        } else {
            ShortcutButtons(state.shortcuts, send, columns)
        }
    }
}

/** Lock, sleep, restart and shut down, as far as the PC allows them. */
@Composable
private fun PowerCard(pcName: String, actions: List<SystemAction>, send: (ClientMessage) -> Unit, columns: Int, modifier: Modifier) {
    var confirming by remember { mutableStateOf<SystemAction?>(null) }
    DockCard(modifier, spacing = 8.dp) {
        CardHeader("PC power")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in actions.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (action in row) {
                        val (icon, label) = systemActionLabel(action)
                        val danger = action == SystemAction.POWER_OFF
                        Column(
                            Modifier.weight(1f).height(if (columns == 2) 60.dp else 56.dp).clip(ChipShape)
                                .background(if (danger) YaeColors.redSoft else YaeColors.raised)
                                .clickable(role = Role.Button) { runSystemAction(action, send) { confirming = it } },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                        ) {
                            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = if (danger) YaeColors.redText else YaeColors.text)
                            Text(
                                label.removeSuffix(" PC"),
                                fontSize = 12.sp,
                                color = if (danger) YaeColors.redText else YaeColors.text,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    confirming?.let { ConfirmSystemAction(pcName, it, send) { confirming = null } }
}
