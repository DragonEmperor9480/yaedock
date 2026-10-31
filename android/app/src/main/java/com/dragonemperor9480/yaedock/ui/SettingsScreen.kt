package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.DockSettings
import com.dragonemperor9480.yaedock.KeepScreenOn
import com.dragonemperor9480.yaedock.R

private val DIM_DELAYS = listOf(1, 2, 5, 10, 15)

/** The app's own settings, stored on this phone, and the ways to the PC list and About. */
@Composable
fun SettingsScreen(
    allowVolumeBoost: Boolean,
    onAllowVolumeBoostChange: (Boolean) -> Unit,
    closeObsAfterRecording: Boolean,
    onCloseObsAfterRecordingChange: (Boolean) -> Unit,
    overlayOnPc: Boolean?,
    onOverlayOnPcChange: (Boolean) -> Unit,
    dock: DockSettings,
    onDockChange: ((DockSettings) -> DockSettings) -> Unit,
    pcsSummary: String,
    onPcs: () -> Unit,
    onAbout: () -> Unit,
    onCommands: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dockCard = @Composable { cardModifier: Modifier -> DockSettingsCard(dock, onDockChange, cardModifier) }
    val controlsCard = @Composable {
        DockCard(Modifier.fillMaxWidth(), padding = 14.dp, spacing = 4.dp) {
            CardHeader("Controls")
            SwitchRow(
                "Volume above 100%",
                "Up to 150%; the PC boosts the sound in software, which can distort it",
                allowVolumeBoost,
                onAllowVolumeBoostChange,
            )
            SwitchRow(
                "Close OBS after recording",
                "Once the video is saved, unless OBS is also streaming or using its virtual camera",
                closeObsAfterRecording,
                onCloseObsAfterRecordingChange,
            )
            // Only known while connected to a PC with game frame rates on.
            overlayOnPc?.let {
                SwitchRow(
                    "MangoHud overlay on PC",
                    "Applies right away, even in a running game. When off, this phone still gets the frame rate",
                    it,
                    onOverlayOnPcChange,
                )
            }
        }
    }
    val links = @Composable { linksModifier: Modifier ->
        Column(
            linksModifier.clip(CardShape).background(YaeColors.card).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            LinkRow(R.drawable.ic_laptop, "Your PCs", pcsSummary, onPcs)
            LinkRow(R.drawable.ic_info, "About YaeDock", "Version ${appVersion()} · open source", onAbout)
            LinkRow(R.drawable.ic_terminal, "PC commands", "Everything yaedock can do in a terminal", onCommands)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (isWide(maxWidth, maxHeight)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScreenHeader("Settings", "Saved on this phone", leading = { BackButton(onBack) })
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    dockCard(Modifier.weight(0.54f).fillMaxHeight().verticalScroll(rememberScrollState()))
                    Column(
                        Modifier.weight(0.46f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        controlsCard()
                        links(Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ScreenHeader("Settings", "Saved on this phone", leading = { BackButton(onBack) })
                dockCard(Modifier.fillMaxWidth())
                controlsCard()
                links(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DockSettingsCard(dock: DockSettings, onDockChange: ((DockSettings) -> DockSettings) -> Unit, modifier: Modifier) {
    DockCard(modifier, padding = 14.dp, spacing = 6.dp) {
        CardHeader("Dock")
        SettingText("Keep the screen on", "While the dashboard is open")
        Row(
            Modifier.fillMaxWidth().clip(ChipShape).background(YaeColors.raised).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((option, label) in listOf(
                KeepScreenOn.WHILE_CHARGING to "While charging",
                KeepScreenOn.ALWAYS to "Always",
                KeepScreenOn.NEVER to "Never",
            )) {
                ChipButton(
                    label,
                    { onDockChange { it.copy(keepScreenOn = option) } },
                    Modifier.weight(1f),
                    selected = dock.keepScreenOn == option,
                    colors = if (dock.keepScreenOn == option) YaeColors.accentSoft to YaeColors.accent else YaeColors.raised to YaeColors.text,
                    height = 40.dp,
                    fontSize = 12.sp,
                )
            }
        }
        Divider()
        SwitchRow(
            "Full screen in landscape",
            "Hides the status and navigation bars; swipe from an edge to see them",
            dock.fullScreen,
        ) { on -> onDockChange { it.copy(fullScreen = on) } }
        SwitchRow(
            "Dim when idle",
            "Darkens the dashboard after a while without a touch; tap to wake",
            dock.dim,
        ) { on -> onDockChange { it.copy(dim = on) } }
        if (dock.dim) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (minutes in DIM_DELAYS) {
                    ChipButton(
                        "$minutes min",
                        { onDockChange { it.copy(dimAfterMinutes = minutes) } },
                        Modifier.weight(1f),
                        selected = dock.dimAfterMinutes == minutes,
                        height = 40.dp,
                    )
                }
            }
        }
        SwitchRow(
            "Burn-in protection",
            "Moves the layout a few pixels now and then, so OLED screens keep no ghost image",
            dock.burnInProtection,
        ) { on -> onDockChange { it.copy(burnInProtection = on) } }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(YaeColors.track))
}

@Composable
private fun SettingText(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, fontSize = 14.sp)
        Muted(description, maxLines = 3)
    }
}

/** A setting with a description and a switch; tapping anywhere on the row flips it. */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingText(title, description, Modifier.weight(1f))
        DockSwitch(checked, onChange, title)
    }
}

/** A row that opens another screen: an icon, a title over a line of detail, a chevron. */
@Composable
fun LinkRow(icon: Int, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(ChipShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(YaeColors.raised), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = YaeColors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Muted(detail)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = YaeColors.muted)
    }
}
