package com.dragonemperor9480.yaedock.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Recording
import com.dragonemperor9480.yaedock.protocol.RunSystemAction
import com.dragonemperor9480.yaedock.protocol.StartRecording
import com.dragonemperor9480.yaedock.protocol.StopRecording
import com.dragonemperor9480.yaedock.protocol.SystemAction
import com.dragonemperor9480.yaedock.protocol.Welcome
import kotlinx.coroutines.delay

/** The places of a connected PC, in the rail (landscape) or the bottom bar (portrait). */
enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    Dock("Dock", R.drawable.ic_dock),
    Overview("Overview", R.drawable.ic_gauge),
    Controls("Controls", R.drawable.ic_sliders),
    Night("Night", R.drawable.ic_moon),
}

/** A phone docked in landscape: the rail on the left and no scrolling. */
fun isWide(maxWidth: Dp, maxHeight: Dp) =
    maxWidth > maxHeight && maxWidth >= 560.dp

/**
 * The frame around a connected PC's screens: a rail of tabs on the left in
 * landscape, a bar of tabs at the bottom in portrait. [content] gets whether
 * the layout is wide.
 */
@Composable
fun ConnectedShell(
    tab: Tab,
    tabs: List<Tab>,
    onTab: (Tab) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (wide: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (isWide(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Rail(tab, tabs, onTab, onSettings)
                Box(Modifier.weight(1f).fillMaxHeight()) { content(true) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content(false) }
                BottomBar(tab, tabs, onTab, Modifier.padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun Rail(tab: Tab, tabs: List<Tab>, onTab: (Tab) -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.width(60.dp).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(YaeColors.card).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AppMark(40)
        Box(Modifier.height(8.dp))
        for (t in tabs) RailButton(t.icon, t.label, selected = t == tab) { onTab(t) }
        Box(Modifier.weight(1f))
        RailButton(settingsIcon(), "Settings", selected = false, onClick = onSettings)
    }
}

@Composable
private fun RailButton(@DrawableRes icon: Int, label: String, selected: Boolean, onClick: () -> Unit) =
    RailButton(painterResource(icon), label, selected, onClick)

@Composable
private fun RailButton(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(ButtonShape)
            .background(if (selected) YaeColors.accentSoft else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) YaeColors.accent else YaeColors.muted)
    }
}

/** The settings gear, outlined like the other icons. */
@Composable
fun settingsIcon(): Painter = rememberVectorPainter(Icons.Outlined.Settings)

@Composable
private fun BottomBar(tab: Tab, tabs: List<Tab>, onTab: (Tab) -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(22.dp)).background(YaeColors.card).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in tabs) {
            val selected = t == tab
            Column(
                Modifier.weight(1f).height(56.dp).clip(ButtonShape)
                    .clickable(role = Role.Tab) { onTab(t) }
                    .semantics { this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Box(
                    Modifier.size(width = 52.dp, height = 28.dp).clip(CircleShape)
                        .background(if (selected) YaeColors.accentSoft else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(t.icon), null, Modifier.size(20.dp), tint = if (selected) YaeColors.accent else YaeColors.muted)
                }
                Text(
                    t.label,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) YaeColors.accent else YaeColors.muted,
                )
            }
        }
    }
}

/** The app's mark: a mint rounded square with a Y. */
@Composable
fun AppMark(sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp * 0.32f).dp)).background(YaeColors.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("Y", color = YaeColors.onAccent, fontWeight = FontWeight.Bold, fontSize = (sizeDp * 0.46f).sp)
    }
}

/** A screen's title row: title and a status line on the left, buttons on the right. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    subtitleColor: Color = YaeColors.muted,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dot?.let { Dot(it) }
                Text(subtitle, fontSize = 12.sp, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}

/** A back arrow in a square button. */
@Composable
fun BackButton(onBack: () -> Unit, label: String = "Back") {
    SquareButton(painterResource(R.drawable.ic_chevron_left), label, onBack)
}

/** The connection's state as a coloured dot and a few words. */
fun statusLine(status: Status): Pair<Color, String> = when (status) {
    is Status.Connected -> YaeColors.accent to "Connected"
    Status.Connecting -> YaeColors.amber to "Connecting…"
    is Status.Waiting -> YaeColors.amber to "Reconnecting…"
    is Status.Rejected -> YaeColors.red to "Not allowed"
    Status.Stopped -> YaeColors.muted to "Not connected"
}

/** The screen recorder's state and what the record button does. */
class RecordControl(val recording: Recording?, val pending: Boolean, val onRecord: (start: Boolean) -> Unit)

/**
 * The record button's behaviour, or null when the PC cannot record. Starting
 * can take a while when OBS has to open first; the button shows progress until
 * the PC reports the change (or gives up, with an error toast).
 */
@Composable
fun rememberRecordControl(
    welcome: Welcome?,
    recording: Recording?,
    closeObsAfterRecording: Boolean,
    send: (ClientMessage) -> Unit,
): RecordControl? {
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(recording?.active) { pending = false }
    LaunchedEffect(pending) {
        if (pending) {
            delay(RECORD_PENDING_MS)
            pending = false
        }
    }
    if (welcome?.recorder != true) return null
    return RecordControl(recording, pending) { start ->
        pending = true
        send(if (start) StartRecording else StopRecording(closeApp = closeObsAfterRecording))
    }
}

/** Opening OBS and starting to record can take this long. */
private const val RECORD_PENDING_MS = 35_000L

/** Idle: a record symbol. Recording: a red "REC 12:48" that stops it. */
@Composable
fun RecordButton(record: RecordControl) {
    val recording = record.recording?.takeIf { it.active }
    when {
        record.pending -> Box(Modifier.size(44.dp).clip(ButtonShape).background(YaeColors.card), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), color = YaeColors.red, strokeWidth = 2.dp)
        }
        recording != null -> Row(
            Modifier.height(44.dp).clip(ButtonShape).background(YaeColors.redSoft)
                .clickable(role = Role.Button, onClickLabel = "Stop recording") { record.onRecord(false) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Dot(YaeColors.red, 9.dp)
            Text(
                "${if (recording.paused) "PAUSED" else "REC"} ${formatTimer(recording.durationMs)}",
                fontFamily = Mono,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = YaeColors.redText,
            )
        }
        else -> Box(
            Modifier.size(44.dp).clip(ButtonShape).background(YaeColors.card)
                .clickable(role = Role.Button, onClickLabel = "Record the PC's screen") { record.onRecord(true) }
                .semantics { contentDescription = "Record the PC's screen" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(16.dp).border(2.dp, YaeColors.red, CircleShape), contentAlignment = Alignment.Center) {
                Dot(YaeColors.red, 8.dp)
            }
        }
    }
}

/** Locking and powering the PC, then the app's own places. */
@Composable
fun PcMenu(
    pcName: String,
    systemActions: List<SystemAction>,
    send: (ClientMessage) -> Unit,
    onSettings: () -> Unit,
    onAllPcs: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<SystemAction?>(null) }
    Box {
        SquareButton(rememberVectorPainter(Icons.Default.MoreVert), "More", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = YaeColors.raised) {
            for (action in systemActions) {
                val (icon, label) = systemActionLabel(action)
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(painterResource(icon), null, Modifier.size(20.dp)) },
                    onClick = {
                        open = false
                        runSystemAction(action, send) { confirming = it }
                    },
                )
            }
            if (systemActions.isNotEmpty()) HorizontalDivider(color = YaeColors.track)
            DropdownMenuItem(
                text = { Text("Settings") },
                leadingIcon = { Icon(settingsIcon(), null, Modifier.size(20.dp)) },
                onClick = { open = false; onSettings() },
            )
            DropdownMenuItem(
                text = { Text("All PCs") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(20.dp)) },
                onClick = { open = false; onAllPcs() },
            )
        }
    }
    confirming?.let { ConfirmSystemAction(pcName, it, send) { confirming = null } }
}

/** Restarting and shutting down are asked about first; the rest happen right away. */
fun runSystemAction(action: SystemAction, send: (ClientMessage) -> Unit, confirm: (SystemAction) -> Unit) {
    if (action == SystemAction.REBOOT || action == SystemAction.POWER_OFF) confirm(action) else send(RunSystemAction(action))
}

@Composable
fun ConfirmSystemAction(pcName: String, action: SystemAction, send: (ClientMessage) -> Unit, onDone: () -> Unit) {
    val restart = action == SystemAction.REBOOT
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = YaeColors.card,
        title = { Text(if (restart) "Restart $pcName?" else "Shut down $pcName?") },
        text = { Text("Unsaved work in open apps on the PC may be lost.", color = YaeColors.muted) },
        confirmButton = {
            TextButton(onClick = { onDone(); send(RunSystemAction(action)) }) {
                Text(if (restart) "Restart" else "Shut down", color = YaeColors.redText)
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

fun systemActionLabel(action: SystemAction): Pair<Int, String> = when (action) {
    SystemAction.LOCK -> R.drawable.ic_lock to "Lock PC"
    SystemAction.SUSPEND -> R.drawable.ic_moon to "Sleep"
    SystemAction.REBOOT -> R.drawable.ic_restart to "Restart…"
    SystemAction.POWER_OFF -> R.drawable.ic_power to "Shut down…"
}

/** What to show instead of a screen's content while not connected. */
@Composable
fun StatusPanel(
    pcName: String,
    status: Status,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (status) {
                is Status.Waiting -> {
                    Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(44.dp), tint = YaeColors.amber)
                    Text("Can't reach $pcName", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Muted(
                        "Check that yaedockd is running on the PC and that both are on the same Wi-Fi. " +
                            "Trying again in ${status.retryInMs / 1000} s.",
                        size = 14.sp,
                        maxLines = 4,
                        textAlign = TextAlign.Center,
                    )
                    ChipButton("Try now", onRetry, selected = true, modifier = Modifier.padding(top = 4.dp))
                }
                is Status.Rejected -> {
                    Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(44.dp), tint = YaeColors.red)
                    Text(
                        "$pcName doesn't recognise this phone",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Muted("It may have been unpaired on the PC. Run `yaedock pair` there and scan the new code.", size = 14.sp, maxLines = 4, textAlign = TextAlign.Center)
                    ChipButton("Pair again", onPairAgain, selected = true, modifier = Modifier.padding(top = 4.dp))
                }
                else -> {
                    CircularProgressIndicator(color = YaeColors.accent)
                    Text("Connecting to $pcName…", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
