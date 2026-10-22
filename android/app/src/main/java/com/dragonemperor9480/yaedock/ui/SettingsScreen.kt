package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dragonemperor9480.yaedock.DockSettings
import com.dragonemperor9480.yaedock.KeepScreenOn

private val DIM_DELAYS = listOf(1, 2, 5, 10, 15)

@Composable
fun SettingsScreen(
    allowVolumeBoost: Boolean,
    onAllowVolumeBoostChange: (Boolean) -> Unit,
    closeObsAfterRecording: Boolean,
    onCloseObsAfterRecordingChange: (Boolean) -> Unit,
    dock: DockSettings,
    onDockChange: ((DockSettings) -> DockSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("Done") }
            }

            Section("Controls")
            SwitchRow(
                title = "Allow volume above 100%",
                description = "Lets the volume slider go up to 150%. The PC boosts the sound in software " +
                    "above 100%, which can make it distort.",
                checked = allowVolumeBoost,
                onChange = onAllowVolumeBoostChange,
            )

            Section("Screen recording")
            SwitchRow(
                title = "Close OBS after recording",
                description = "When you stop a recording, OBS Studio closes once the video is saved. " +
                    "It stays open if it is also streaming or using its virtual camera.",
                checked = closeObsAfterRecording,
                onChange = onCloseObsAfterRecordingChange,
            )

            Section("Dock")
            Text("Keep the screen on", style = MaterialTheme.typography.titleMedium)
            Text(
                "While the dashboard is open.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((option, label) in listOf(
                KeepScreenOn.WHILE_CHARGING to "While charging",
                KeepScreenOn.ALWAYS to "Always",
                KeepScreenOn.NEVER to "Never",
            )) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(dock.keepScreenOn == option, role = Role.RadioButton) {
                            onDockChange { it.copy(keepScreenOn = option) }
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = dock.keepScreenOn == option, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "Full screen in landscape",
                description = "Hides the status and navigation bars on the dashboard. Swipe from an edge to see them.",
                checked = dock.fullScreen,
                onChange = { on -> onDockChange { it.copy(fullScreen = on) } },
            )
            SwitchRow(
                title = "Dim when idle",
                description = "Darkens the dashboard after a while without a touch, while the screen is kept on. " +
                    "Tap to wake it.",
                checked = dock.dim,
                onChange = { on -> onDockChange { it.copy(dim = on) } },
            )
            if (dock.dim) {
                // Wraps onto a second line when large text leaves no room for all five.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (minutes in DIM_DELAYS) {
                        FilterChip(
                            selected = dock.dimAfterMinutes == minutes,
                            onClick = { onDockChange { it.copy(dimAfterMinutes = minutes) } },
                            label = { Text("$minutes min", maxLines = 1) },
                        )
                    }
                }
            }
            SwitchRow(
                title = "Burn-in protection",
                description = "Moves the dashboard by a few pixels every few minutes, so a layout shown for " +
                    "hours does not leave a ghost image on OLED screens.",
                checked = dock.burnInProtection,
                onChange = { on -> onDockChange { it.copy(burnInProtection = on) } },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        // The whole row toggles, so the switch itself takes no clicks.
        Switch(checked = checked, onCheckedChange = null)
    }
}
