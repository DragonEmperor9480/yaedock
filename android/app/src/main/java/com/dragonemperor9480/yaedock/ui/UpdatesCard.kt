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
        if (state.pc != null) VersionRow("PC", state.pc.ifEmpty { null }, newest, unknown = "Too old to tell; run yaedock update on the PC")
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
