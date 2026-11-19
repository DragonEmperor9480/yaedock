package com.dragonemperor9480.yaedock.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
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
import com.dragonemperor9480.yaedock.NavBarMode
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.net.Via
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Recording
import com.dragonemperor9480.yaedock.protocol.RunSystemAction
import com.dragonemperor9480.yaedock.protocol.StartRecording
import com.dragonemperor9480.yaedock.protocol.StopRecording
import com.dragonemperor9480.yaedock.protocol.SystemAction
import com.dragonemperor9480.yaedock.protocol.Welcome
import kotlinx.coroutines.delay

/** The places of a connected PC: the rail in landscape, the bar at the bottom in portrait. */
enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    Home("Home", R.drawable.ic_home),
    Stats("Stats", R.drawable.ic_gauge),
    Controls("Controls", R.drawable.ic_sliders),
}

/** A phone docked in landscape: nothing scrolls, and space is short. */
fun isWide(maxWidth: Dp, maxHeight: Dp) =
    maxWidth > maxHeight && maxWidth >= 560.dp

/** How long tabs brought up by a tap or a swipe stay. */
private const val NAV_HIDE_MS = 4_000L
private val RailWidth = 76.dp
private val BarHeight = 72.dp

/**
 * The frame around a connected PC's screens. In landscape the tabs are a rail
 * on the left with Settings at its foot; in portrait a bar along the bottom.
 * [navBar] says when they stay; otherwise they come in for a few seconds after
 * a tap on anything that is not a control, or a swipe from their edge, and a
 * second tap hides them. [content] gets whether the layout is wide.
 */
@Composable
fun ConnectedShell(
    tab: Tab,
    tabs: List<Tab>,
    navBar: NavBarMode,
    onTab: (Tab) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (wide: Boolean) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = isWide(maxWidth, maxHeight)
        val pinned = when (navBar) {
            NavBarMode.ALWAYS -> true
            NavBarMode.PORTRAIT -> !wide
            NavBarMode.LANDSCAPE -> wide
            NavBarMode.ON_TAP -> false
        }
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(shown, tab) {
            if (shown) {
                delay(NAV_HIDE_MS)
                shown = false
            }
        }
        val pick = { t: Tab -> onTab(t); shown = false }
        Box(
            Modifier.fillMaxSize()
                // Runs after the screen's own controls, so a press on one never gets here.
                .pointerInput(pinned) { if (!pinned) detectTapGestures { shown = !shown } },
        ) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    if (pinned) Rail(tab, tabs, pick, onSettings, Modifier.fillMaxHeight())
                    Box(Modifier.weight(1f).fillMaxHeight().padding(start = if (pinned) 0.dp else 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                        content(true)
                    }
                }
                if (!pinned) {
                    AnimatedVisibility(
                        visible = shown,
                        modifier = Modifier.align(Alignment.CenterStart),
                        enter = slideInHorizontally { -it } + fadeIn(),
                        exit = slideOutHorizontally { -it } + fadeOut(),
                    ) {
                        Rail(tab, tabs, pick, onSettings, Modifier.fillMaxHeight().shadow(14.dp).background(YaeColors.card))
                    }
                    if (!shown) EdgeHandle(vertical = true, Modifier.align(Alignment.CenterStart)) { shown = true }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (pinned) 8.dp else 12.dp)) {
                        content(false)
                    }
                    if (pinned) BottomBar(tab, tabs, pick)
                }
                if (!pinned) {
                    AnimatedVisibility(
                        visible = shown,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        BottomBar(tab, tabs, pick, Modifier.shadow(14.dp))
                    }
                    if (!shown) EdgeHandle(vertical = false, Modifier.align(Alignment.BottomCenter)) { shown = true }
                }
            }
        }
    }
}

/** The thin line that brings hidden tabs back, by a tap or a swipe away from its edge. */
@Composable
private fun EdgeHandle(vertical: Boolean, modifier: Modifier, onShow: () -> Unit) {
    Box(
        (if (vertical) modifier.size(width = 24.dp, height = 120.dp) else modifier.size(width = 120.dp, height = 24.dp))
            .pointerInput(vertical) {
                if (vertical) {
                    detectHorizontalDragGestures { _, dragAmount -> if (dragAmount > 0) onShow() }
                } else {
                    detectVerticalDragGestures { _, dragAmount -> if (dragAmount < 0) onShow() }
                }
            }
            .clickable(role = Role.Button, onClickLabel = "Show the tabs", onClick = onShow),
        contentAlignment = if (vertical) Alignment.CenterStart else Alignment.BottomCenter,
    ) {
        Box(
            (if (vertical) Modifier.padding(start = 5.dp).size(width = 4.dp, height = 44.dp) else Modifier.padding(bottom = 5.dp).size(width = 44.dp, height = 4.dp))
                .clip(CircleShape).background(YaeColors.handle),
        )
    }
}

/** The landscape tabs: a column of icons with their names, and Settings at the foot. */
@Composable
private fun Rail(tab: Tab, tabs: List<Tab>, onTab: (Tab) -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    Column(
        modifier.width(RailWidth).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (t in tabs) NavItem(painterResource(t.icon), t.label, t == tab, showLabel = true) { onTab(t) }
        Spacer(Modifier.weight(1f))
        NavItem(settingsIcon(), "Settings", selected = false, showLabel = false, onClick = onSettings)
    }
}

/** The portrait tabs, along the bottom; Settings sits in each screen's header there. */
@Composable
private fun BottomBar(tab: Tab, tabs: List<Tab>, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(BarHeight).background(YaeColors.card).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in tabs) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                NavItem(painterResource(t.icon), t.label, t == tab, showLabel = true) { onTab(t) }
            }
        }
    }
}

/** One tab: an icon in a pill that fills when chosen, and its name under it. */
@Composable
private fun NavItem(icon: Painter, label: String, selected: Boolean, showLabel: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                if (!showLabel) contentDescription = label
                this.selected = selected
            }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 30.dp).clip(CircleShape)
                .background(if (selected) YaeColors.accentSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (selected) YaeColors.accent else YaeColors.navIdle)
        }
        if (showLabel) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) YaeColors.text else YaeColors.muted,
                maxLines = 1,
            )
        }
    }
}

/** The settings gear, outlined like the other icons. */
@Composable
fun settingsIcon(): Painter = rememberVectorPainter(Icons.Outlined.Settings)

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
@Composable @ReadOnlyComposable
fun statusLine(status: Status): Pair<Color, String> = when (status) {
    is Status.Connected -> YaeColors.accent to if (status.via == Via.CABLE) "Connected by cable" else "Connected"
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
