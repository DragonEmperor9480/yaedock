package com.dragonemperor9480.yaedock.ui

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Recording
import com.dragonemperor9480.yaedock.protocol.RunSystemAction
import com.dragonemperor9480.yaedock.protocol.StartRecording
import com.dragonemperor9480.yaedock.protocol.StopRecording
import com.dragonemperor9480.yaedock.protocol.SystemAction
import com.dragonemperor9480.yaedock.protocol.Welcome
import kotlinx.coroutines.delay

/** The places of a connected PC, in the bar of tabs. */
enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    Dock("Dock", R.drawable.ic_dock),
    Overview("Overview", R.drawable.ic_gauge),
    Controls("Controls", R.drawable.ic_sliders),
    Night("Night", R.drawable.ic_moon),
}

/** A phone docked in landscape: nothing scrolls, and space is short. */
fun isWide(maxWidth: Dp, maxHeight: Dp) =
    maxWidth > maxHeight && maxWidth >= 560.dp

/** How long a bar brought up by a tap stays. */
private const val NAV_BAR_HIDE_MS = 4_000L
private val BarGap = 10.dp
private val BarHeightWide = 58.dp
private val BarHeightTall = 64.dp
private val GlassTint = Color(0x8C262E3A)
private val GlassEdge = Color(0x33EAF0F6)
private val GlassBlur = 22.dp

/** Older phones cannot blur what is behind the bar, so it is nearly solid there. */
private val GlassFallback = Color(0xF21A2029)

/**
 * The frame around a connected PC's screens, with a pill of tabs at the bottom.
 * [navBar] says when the pill stays; otherwise it floats over the screen for a
 * moment after a tap on anything that is not a control, or on its handle, and a
 * second tap hides it. [content] gets whether the layout is wide.
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
                delay(NAV_BAR_HIDE_MS)
                shown = false
            }
        }
        val barHeight = if (wide) BarHeightWide else BarHeightTall
        val backdrop = rememberGraphicsLayer()
        var rootOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { rootOrigin = it.positionInRoot() }
                // Runs after the screen's own controls, so a press on one never gets here.
                .pointerInput(pinned) { if (!pinned) detectTapGestures { shown = !shown } },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .drawWithContent {
                        backdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(backdrop)
                    }
                    .padding(
                        start = if (wide) 12.dp else 16.dp,
                        end = if (wide) 12.dp else 16.dp,
                        top = 12.dp,
                        bottom = if (pinned) barHeight + BarGap * 2 else 12.dp,
                    ),
            ) {
                content(wide)
            }
            AnimatedVisibility(
                visible = pinned || shown,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NavBar(
                    tab,
                    tabs,
                    onTab,
                    onSettings.takeIf { wide },
                    backdrop,
                    { rootOrigin },
                    (if (wide) Modifier else Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        .padding(bottom = BarGap)
                        .height(barHeight),
                )
            }
            if (!pinned && !shown) NavBarHandle(Modifier.align(Alignment.BottomCenter)) { shown = true }
        }
    }
}

/** The thin line that brings up a hidden bar, by a tap or a swipe up. */
@Composable
private fun NavBarHandle(modifier: Modifier, onShow: () -> Unit) {
    Box(
        modifier.size(width = 120.dp, height = 24.dp)
            .pointerInput(Unit) { detectVerticalDragGestures { _, dragAmount -> if (dragAmount < 0) onShow() } }
            .clickable(role = Role.Button, onClickLabel = "Show the navigation bar", onClick = onShow),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.padding(bottom = 5.dp).size(width = 44.dp, height = 4.dp).clip(CircleShape).background(Color(0xFF4A5565)))
    }
}

/** The pill of tabs, of frosted glass over [backdrop]. With [onSettings] it has that button too. */
@Composable
private fun NavBar(
    tab: Tab,
    tabs: List<Tab>,
    onTab: (Tab) -> Unit,
    onSettings: (() -> Unit)?,
    backdrop: GraphicsLayer,
    rootOrigin: () -> Offset,
    modifier: Modifier,
) {
    val frost = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier
            .shadow(14.dp, CircleShape)
            .clip(CircleShape)
            .onGloballyPositioned { origin = it.positionInRoot() - rootOrigin() }
            .drawBehind {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val radius = GlassBlur.toPx()
                    frost.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    frost.record { translate(-origin.x, -origin.y) { drawLayer(backdrop) } }
                    drawLayer(frost)
                    drawRect(GlassTint)
                } else {
                    drawRect(GlassFallback)
                }
            }
            .border(1.dp, GlassEdge, CircleShape)
            .padding(horizontal = if (onSettings != null) 14.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (onSettings != null) 12.dp else 0.dp),
    ) {
        for (t in tabs) {
            val selected = t == tab
            if (onSettings != null) {
                NavIcon(painterResource(t.icon), t.label, selected) { onTab(t) }
            } else {
                Column(
                    Modifier.weight(1f).height(56.dp).clip(CircleShape)
                        .clickable(role = Role.Tab) { onTab(t) }
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier.size(width = 52.dp, height = 28.dp).clip(CircleShape)
                            .background(if (selected) NavSelected else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(t.icon), null, Modifier.size(20.dp), tint = if (selected) YaeColors.accent else NavIdle)
                    }
                    Text(
                        t.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) YaeColors.accent else NavIdle,
                    )
                }
            }
        }
        onSettings?.let { NavIcon(settingsIcon(), "Settings", selected = false, onClick = it) }
    }
}

private val NavSelected = Color(0x335BE7C4)
private val NavIdle = Color(0xFFC9D2DE)

@Composable
private fun NavIcon(icon: Painter, label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) NavSelected else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (selected) YaeColors.accent else NavIdle)
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
