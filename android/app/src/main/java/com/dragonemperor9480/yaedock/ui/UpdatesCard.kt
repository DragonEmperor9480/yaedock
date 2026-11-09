package com.dragonemperor9480.yaedock.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.PcUpdate
import com.dragonemperor9480.yaedock.protocol.UpdateStage
import com.dragonemperor9480.yaedock.update.AppInstall
import com.dragonemperor9480.yaedock.update.Release
import com.dragonemperor9480.yaedock.update.UpdateCheck
import com.dragonemperor9480.yaedock.update.Version

/** What the Updates card shows and does. */
class UpdatesState(
    val check: UpdateCheck,
    /** This app's version. */
    val app: Version?,
    /** The connected PC's version, null when no PC is connected, empty when it is too old to say. */
    val pc: String?,
    /** The newest version an earlier check found, until this one says more. */
    val remembered: String,
    val autoCheck: Boolean,
    val onAutoCheckChange: (Boolean) -> Unit,
    val onCheck: () -> Unit,
    /** Downloading and installing the app's update. */
    val install: AppInstall,
    val onInstall: (Release) -> Unit,
    /** Why the PC cannot update itself, such as a copy owned by root; null if it can. */
    val pcCannotUpdate: String?,
    val pcUpdate: PcUpdate,
    val onUpdatePc: (String) -> Unit,
)

/**
 * The app's and the PC's versions against the newest release, a button to
 * check now, the release notes, and the switch for a daily check.
 */
@Composable
fun UpdatesCard(state: UpdatesState, modifier: Modifier = Modifier) {
    val release = (state.check as? UpdateCheck.Checked)?.newest
    val newest = release?.version ?: Version.parse(state.remembered)
    DockCard(modifier, padding = 14.dp, spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Updates", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Muted(status(state.check, newest), size = 12.sp, maxLines = 2)
            }
            val checking = state.check == UpdateCheck.Checking
            ChipButton(
                if (checking) "Checking…" else "Check now",
                state.onCheck,
                Modifier.width(112.dp),
                icon = painterResource(R.drawable.ic_refresh),
                height = 38.dp,
                enabled = !checking,
            )
        }
        VersionRow("App", state.app?.toString(), newest, unknown = "")
        if (release != null && state.app != null && release.version > state.app) AppInstallRow(release, state.install, state.onInstall)
        if (state.pc != null) {
            VersionRow("PC", state.pc.ifEmpty { null }, newest, unknown = "Too old to tell; run yaedock update on the PC")
            val pc = Version.parse(state.pc)
            if (newest != null && pc != null && newest > pc) {
                PcUpdateRow(newest, state.pcCannotUpdate, state.pcUpdate, state.onUpdatePc)
            } else {
                PcResult(state.pcUpdate)
            }
        }
        if (release != null && release.notes.isNotEmpty() && state.app != null && release.version > state.app) Notes(release)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Switch) { state.onAutoCheckChange(!state.autoCheck) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Check automatically", fontSize = 14.sp)
                Muted("Once a day, the app asks GitHub for new releases", size = 12.sp, maxLines = 2)
            }
            DockSwitch(state.autoCheck, state.onAutoCheckChange, "Check for updates automatically")
        }
    }
}

private fun status(check: UpdateCheck, newest: Version?): String = when (check) {
    UpdateCheck.Idle -> if (newest != null) "Newest release: $newest" else "Not checked yet"
    UpdateCheck.Checking -> "Asking GitHub…"
    is UpdateCheck.Failed -> check.message
    is UpdateCheck.Checked -> check.newest?.let { "Newest release: ${it.version}" } ?: "No releases yet"
}

/** One program's version and whether a newer release exists. */
@Composable
private fun VersionRow(label: String, version: String?, newest: Version?, unknown: String) {
    val current = version?.let { Version.parse(it) }
    val (text, color) = when {
        version == null -> unknown to YaeColors.muted
        newest == null || current == null -> "" to YaeColors.muted
        newest > current -> "$newest available" to YaeColors.accent
        else -> "Up to date" to YaeColors.muted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.width(36.dp), fontSize = 13.sp, color = YaeColors.muted)
        if (version != null) Text(version, fontFamily = Mono, fontSize = 13.sp)
        Text(
            text,
            Modifier.weight(1f),
            fontSize = 13.sp,
            color = color,
            fontWeight = if (color == YaeColors.accent) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The button that installs the app's update, or how far installing it got. */
@Composable
private fun AppInstallRow(release: Release, install: AppInstall, onInstall: (Release) -> Unit) {
    val (text, busy) = when (install) {
        AppInstall.Idle -> null to false
        is AppInstall.Downloading -> (install.percent?.let { "Downloading… $it%" } ?: "Downloading…") to true
        AppInstall.Verifying -> "Checking the download…" to true
        AppInstall.Installing -> "Confirm the update in Android's prompt" to false
        is AppInstall.Failed -> install.message to false
    }
    ChipButton(
        "Update app to ${release.version}",
        { onInstall(release) },
        Modifier.fillMaxWidth(),
        selected = true,
        height = 40.dp,
        enabled = !busy,
    )
    text?.let { Muted(it, size = 12.sp, maxLines = 3) }
}

/** The button that tells the PC to update itself, or why it cannot, and how far it got. */
@Composable
private fun PcUpdateRow(newest: Version, cannot: String?, update: PcUpdate, onUpdatePc: (String) -> Unit) {
    if (cannot != null) {
        Muted(cannot, size = 12.sp, maxLines = 3)
        return
    }
    ChipButton(
        "Update PC to $newest",
        { onUpdatePc(newest.toString()) },
        Modifier.fillMaxWidth(),
        selected = true,
        height = 40.dp,
        enabled = update !is PcUpdate.Running,
    )
    PcResult(update)
}

/** How the PC's update is going, or how it ended. */
@Composable
private fun PcResult(update: PcUpdate) {
    val text = when (update) {
        PcUpdate.Idle -> return
        is PcUpdate.Running -> when (update.stage) {
            null -> "Asking the PC…"
            UpdateStage.DOWNLOADING -> update.percent?.let { "The PC is downloading… $it%" } ?: "The PC is downloading…"
            UpdateStage.VERIFYING -> "The PC is checking the download…"
            UpdateStage.INSTALLING -> "The PC is installing…"
            UpdateStage.RESTARTING -> "YaeDock restarts on the PC…"
        }
        is PcUpdate.Failed -> update.message
        is PcUpdate.Done -> "The PC now runs ${update.version}"
    }
    Muted(text, size = 12.sp, maxLines = 3)
}

/** The release notes, folded under "What's new". */
@Composable
private fun Notes(release: Release) {
    var open by rememberSaveable(release.version.toString()) { mutableStateOf(false) }
    Column {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = if (open) "Hide" else "Show") { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("What's new in ${release.version}", fontSize = 13.sp, color = YaeColors.accent)
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                null,
                Modifier.size(14.dp).rotate(if (open) 90f else 0f),
                tint = YaeColors.accent,
            )
        }
        AnimatedVisibility(open) {
            Text(release.notes, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.Unspecified, lineHeight = 18.sp)
        }
    }
}
