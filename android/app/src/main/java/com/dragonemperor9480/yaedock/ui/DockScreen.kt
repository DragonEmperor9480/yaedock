package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import com.dragonemperor9480.yaedock.net.Via
import com.dragonemperor9480.yaedock.protocol.Battery
import com.dragonemperor9480.yaedock.protocol.BatteryState
import com.dragonemperor9480.yaedock.protocol.SystemAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.dragonemperor9480.yaedock.net.usbSpeed
import com.dragonemperor9480.yaedock.net.wifiSummary
import com.dragonemperor9480.yaedock.net.wifiDetails
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.dragonemperor9480.yaedock.protocol.Drive
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.HomePart
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.SetMic
import com.dragonemperor9480.yaedock.protocol.SetOverlay
import com.dragonemperor9480.yaedock.protocol.MediaArt
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.TakeScreenshot
import com.dragonemperor9480.yaedock.protocol.Welcome
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home: the things reached for on a dock. The PC's chip, live tiles (four, then
 * one for each drive; Stats has the rest), the time, what is playing, quick actions and sound. In
 * landscape nothing scrolls; in portrait everything stacks and scrolls.
 */
@Composable
fun DockScreen(
    pcName: String,
    status: Status,
    stats: Stats?,
    state: State?,
    /** The cover the PC sent last; shown while it is the one [State.media] names. */
    art: MediaArt?,
    history: History,
    wide: Boolean,
    allowVolumeBoost: Boolean,
    userName: String,
    record: RecordControl?,
    send: (ClientMessage) -> Unit,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    onSettings: () -> Unit,
    onAllPcs: () -> Unit,
    /** The link's last round trip, for the PC menu. */
    delayMs: Long?,
    /** Times the link again; called while the PC menu is open. */
    onMeasureDelay: () -> Unit,
    /** What the user hid from Home in Settings. */
    hidden: Set<HomePart>,
    onOverview: () -> Unit,
    onNight: () -> Unit,
) {
    val welcome = (status as? Status.Connected)?.welcome
    val header = @Composable { note: String? ->
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PcChip(pcName, status, welcome?.systemActions.orEmpty(), send, onAllPcs, delayMs, onMeasureDelay)
            if (note != null) Muted(note, Modifier.weight(1f), size = 12.sp) else Spacer(Modifier.weight(1f))
            RoundButton(R.drawable.ic_moon, "Night mode", onNight)
            if (!wide) RoundButton(null, "Settings", onSettings)
            LocalHeaderTabs.current?.invoke()
        }
    }
    if (welcome == null || stats == null || state == null) {
        Column(Modifier.fillMaxSize()) {
            header(null)
            StatusPanel(pcName, status, onRetry, onPairAgain)
        }
        return
    }
    val note = listOfNotNull(state.power?.active?.let(::profileLabel), "up ${formatUptime(stats.uptime)}").joinToString(" · ")
    val shows = { part: HomePart -> part !in hidden }
    // The stats card goes too when every one of its tiles is hidden.
    val tiles = shows(HomePart.STATS) && HomePart.entries.any { it.tile && shows(it) }
    val clock = shows(HomePart.CLOCK)
    val media = shows(HomePart.MEDIA)
    val actions = shows(HomePart.ACTIONS)
    val controls = shows(HomePart.CONTROLS)
    if (!tiles && !clock && !media && !actions && !controls) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            header(note)
            EmptyHome(onSettings, Modifier.weight(1f).fillMaxWidth())
        }
        return
    }
    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            header(note)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val left = clock || media
                val right = tiles || actions || controls
                if (left) {
                    // A narrow column beside the rest, or all the width when it is alone.
                    Column(
                        (if (right) Modifier.width(236.dp) else Modifier.weight(1f)).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (clock) ClockCard(userName, large = true, Modifier.weight(1f).fillMaxWidth())
                        if (media) MediaCard(state.media, art, send, stacked = true, if (clock) Modifier else Modifier.weight(1f))
                    }
                }
                if (right) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (tiles) StatTiles(stats, history, columns = 4, onOverview, Modifier.weight(1f).fillMaxWidth(), hidden = hidden)
                        if (actions || controls) {
                            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (actions) QuickActions(welcome, state, record, send, columns = 2, Modifier.weight(1f).fillMaxHeight())
                                if (controls) QuickControls(state, allowVolumeBoost, send, Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header(null)
            Muted(note, Modifier.padding(start = 4.dp), size = 12.sp)
            if (tiles) StatTiles(stats, history, columns = 2, onOverview, Modifier.fillMaxWidth(), rowHeight = 105.dp, hidden = hidden)
            if (clock) ClockCard(userName, large = false, Modifier.fillMaxWidth())
            if (media) MediaCard(state.media, art, send, stacked = false)
            if (actions) QuickActions(welcome, state, record, send, columns = 4, Modifier.fillMaxWidth().height(78.dp))
            if (controls) QuickControls(state, allowVolumeBoost, send, Modifier.fillMaxWidth())
            BarRoom()
        }
    }
}

/** Home with every card hidden: a word on why it is empty, and the way back to Settings. */
@Composable
private fun EmptyHome(onSettings: () -> Unit, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        Text("Everything on Home is hidden", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Muted("Choose what Home shows in Settings, under Tabs.", size = 13.sp)
        Text(
            "Open Settings",
            Modifier.clip(CircleShape).background(YaeColors.accentSoft).clickable(role = Role.Button, onClick = onSettings)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            color = YaeColors.accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A round icon button for a header; with no icon, the settings gear. */
@Composable
private fun RoundButton(icon: Int?, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(YaeColors.card)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (icon != null) painterResource(icon) else settingsIcon(), null, Modifier.size(20.dp), tint = YaeColors.text)
    }
}

/**
 * The PC this phone is showing: how it is linked, its name, and a menu to lock
 * or power it, or to switch to another PC.
 */
@Composable
private fun PcChip(
    pcName: String,
    status: Status,
    systemActions: List<SystemAction>,
    send: (ClientMessage) -> Unit,
    onAllPcs: () -> Unit,
    delayMs: Long?,
    onMeasureDelay: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<SystemAction?>(null) }
    val (statusColor, statusText) = statusLine(status)
    val connected = status as? Status.Connected
    Box {
        Row(
            Modifier.height(44.dp).clip(CircleShape).background(YaeColors.card)
                .clickable(role = Role.Button, onClickLabel = "PC menu") { open = true }
                .semantics(mergeDescendants = true) { contentDescription = "$pcName, $statusText" }
                .padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(if (connected != null) YaeColors.accentSoft else YaeColors.raised),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    connected?.via == Via.CABLE -> Icon(painterResource(R.drawable.ic_cable), null, Modifier.size(16.dp), tint = YaeColors.accent)
                    connected != null -> Icon(painterResource(R.drawable.ic_wifi), null, Modifier.size(16.dp), tint = YaeColors.accent)
                    else -> Dot(statusColor)
                }
            }
            Text(pcName, fontFamily = Grotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            if (connected == null) Muted(statusText, size = 12.sp)
            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(16.dp), tint = YaeColors.muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = YaeColors.raised) {
            Muted(if (connected != null) statusText else "$pcName · $statusText", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), size = 12.sp)
            if (connected != null) LinkInfo(connected, delayMs, onMeasureDelay)
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
                text = { Text("All PCs") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(20.dp)) },
                onClick = { open = false; onAllPcs() },
            )
        }
    }
    confirming?.let { ConfirmSystemAction(pcName, it, send) { confirming = null } }
}

/**
 * How the phone reaches the PC, under the menu's status line: the cable's
 * speed, or the Wi-Fi band, generation, link speed and signal, then the
 * round trip. While shown, it times the link and reads the Wi-Fi each second.
 */
@Composable
private fun LinkInfo(connected: Status.Connected, delayMs: Long?, onMeasureDelay: () -> Unit) {
    val context = LocalContext.current
    val cable = connected.via == Via.CABLE
    var wifi by remember { mutableStateOf(if (cable) null else wifiDetails(context)) }
    LaunchedEffect(cable) {
        while (true) {
            onMeasureDelay()
            if (!cable) wifi = wifiDetails(context)
            delay(1_000)
        }
    }
    val link = if (cable) {
        connected.welcome.usbSpeedMbps?.let(::usbSpeed) ?: "USB cable"
    } else {
        wifi?.let(::wifiSummary)?.ifEmpty { null } ?: "Wi-Fi"
    }
    val round = delayMs?.let { "Delay $it ms" } ?: "Delay …"
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(link, fontSize = 13.sp, color = YaeColors.text)
        Muted(round, size = 12.sp)
    }
}

/**
 * The live tiles: CPU, RAM, network, the battery (or the uptime on a PC
 * without one), then one for each drive. A tap opens Stats. With [rowHeight] (portrait) they wrap into as many rows as
 * they need; without it (landscape) they share the height in one row, and
 * when there are more than [columns] the row scrolls sideways, the next
 * tile peeking in at the edge.
 */
@Composable
private fun StatTiles(
    stats: Stats,
    history: History,
    columns: Int,
    onOverview: () -> Unit,
    modifier: Modifier,
    rowHeight: Dp? = null,
    hidden: Set<HomePart> = emptySet(),
) {
    val tiles = listOf<Pair<HomePart, @Composable (Modifier) -> Unit>>(
        HomePart.CPU to { m ->
            val cpu = stats.cpu?.total
            StatTile("CPU", m, extra = cpuTemperature(stats)?.let { String.format(Locale.ROOT, "%.0f°", it) }, extraColor = cpuTemperature(stats)?.let { temperatureColor(it) }) {
                BigNumber(cpu?.roundToInt()?.toString() ?: "–", "%")
                Bar(cpu?.div(100f))
            }
        },
        HomePart.RAM to { m ->
            val mem = stats.mem?.takeIf { it.total > 0 }
            StatTile("RAM", m, extra = mem?.let { "${gib(it.used)} GiB" }) {
                BigNumber(mem?.let { (it.used * 100f / it.total).roundToInt().toString() } ?: "–", "%")
                Bar(mem?.let { it.used.toFloat() / it.total })
            }
        },
        HomePart.NETWORK to { m ->
            StatTile("Network", m) {
                val (down, downUnit) = shortBytes(stats.net?.rx ?: 0)
                val (up, upUnit) = shortBytes(stats.net?.tx ?: 0)
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("↓ $down", fontFamily = Grotesk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Muted(" $downUnit/s", Modifier.padding(bottom = 3.dp), size = 11.sp)
                    }
                    Text("↑ $up $upUnit/s", fontFamily = Grotesk, fontSize = 13.sp, color = YaeColors.muted, maxLines = 1)
                }
                Sparkline(history.netRx, YaeColors.accent, Modifier.fillMaxWidth().height(14.dp))
            }
        },
        HomePart.BATTERY to { m ->
            val battery = stats.battery
            if (battery == null) {
                StatTile("Uptime", m) {
                    Text(formatUptime(stats.uptime), fontFamily = Grotesk, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Muted("since the PC started", size = 12.sp)
                }
            } else {
                StatTile("Battery", m) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BatteryIcon(battery)
                        BigNumber(battery.percent.roundToInt().toString(), "%", size = 26.sp)
                    }
                    Muted(batteryStatus(battery), size = 11.sp, maxLines = 2)
                }
            }
        },
    ).plus(stats.drives.orEmpty().map { drive -> HomePart.DRIVES to @Composable { m: Modifier -> DriveTile(drive, m) } })
        .filter { (part, _) -> part !in hidden }
        .map { (_, tile) -> tile }
    DockCard(
        modifier.clip(CardShape).clickable(role = Role.Button, onClickLabel = "Open Stats", onClick = onOverview),
        padding = 12.dp,
        spacing = 10.dp,
    ) {
        if (rowHeight != null) {
            for (row in tiles.chunked(columns)) {
                Row(Modifier.height(rowHeight).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gap = 10.dp
                val fits = tiles.size <= columns
                // Narrow enough that a sliver of the next tile shows, when there is one.
                val width = (maxWidth - gap * (columns - 1)) / columns * (if (fits) 1f else 0.92f)
                Row(
                    Modifier.fillMaxSize().then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    for (tile in tiles) tile(Modifier.width(width).fillMaxHeight())
                }
            }
        }
    }
}

/** How full a drive is: its name, the share used and what is free, and a bar. */
@Composable
private fun DriveTile(drive: Drive, modifier: Modifier) {
    val used = if (drive.total > 0) 1f - drive.free.toFloat() / drive.total else 0f
    val (free, unit) = shortBytes(drive.free)
    // The free space sits beside the number, so the name has the whole top
    // line; in a narrow tile (landscape), which is tall, it goes under it.
    StatTile(drive.name, modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val number = @Composable { BigNumber((used * 100).roundToInt().toString(), "%") }
            if (maxWidth < 150.dp) {
                Column {
                    number()
                    Muted("$free $unit free", size = 12.sp, maxLines = 1)
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    number()
                    Spacer(Modifier.weight(1f))
                    Muted("$free $unit free", Modifier.padding(bottom = 3.dp, start = 6.dp), size = 12.sp, maxLines = 1)
                }
            }
        }
        Bar(used, color = driveColor(used))
    }
}

/** Mint while there is room, amber from 90% full, red from 97%. */
@Composable
private fun driveColor(used: Float): Color = when {
    used >= 0.97f -> YaeColors.red
    used >= 0.90f -> YaeColors.amber
    else -> YaeColors.accent
}

/** One live number in a raised square: its name, an aside on the right, then the reading. */
@Composable
private fun StatTile(
    label: String,
    modifier: Modifier,
    extra: String? = null,
    extraColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(YaeColors.raised).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), Modifier.weight(1f), style = CardLabel, maxLines = 1)
            extra?.let { Text(it, fontSize = 12.sp, color = extraColor ?: YaeColors.muted, maxLines = 1) }
        }
        content()
    }
}

@Composable
private fun BigNumber(value: String, unit: String, size: TextUnit = 34.sp) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontFamily = Grotesk, fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(unit, Modifier.padding(bottom = 3.dp, start = 1.dp), fontFamily = Grotesk, fontSize = 15.sp, color = YaeColors.muted)
    }
}

/** A thin bar filled to [fraction], or empty while unknown. */
@Composable
private fun Bar(fraction: Float?, color: Color = YaeColors.accent) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(YaeColors.track)) {
        if (fraction != null) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

/**
 * A battery drawn filled to its charge: mint while fine, amber at 20% or less,
 * red at 10% or less, with a bolt while it charges.
 */
@Composable
private fun BatteryIcon(battery: Battery) {
    val fill = batteryColor(battery.percent, YaeColors.accent, YaeColors.amber, YaeColors.red)
    val outline = YaeColors.muted
    val charging = battery.state == BatteryState.CHARGING || battery.state == BatteryState.FULL
    Box(Modifier.size(width = 32.dp, height = 18.dp), contentAlignment = Alignment.CenterStart) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.6.dp.toPx()
            val body = Size(size.width - 4.dp.toPx(), size.height)
            drawRoundRect(outline, topLeft = Offset(stroke / 2, stroke / 2), size = Size(body.width - stroke, body.height - stroke), cornerRadius = CornerRadius(5.dp.toPx()), style = Stroke(stroke))
            drawRoundRect(outline, topLeft = Offset(body.width + 1.dp.toPx(), size.height * 0.32f), size = Size(2.5.dp.toPx(), size.height * 0.36f), cornerRadius = CornerRadius(1.dp.toPx()))
            val inset = 3.dp.toPx()
            val inner = Size((body.width - inset * 2) * (battery.percent / 100f).coerceIn(0.04f, 1f), body.height - inset * 2)
            drawRoundRect(fill, topLeft = Offset(inset, inset), size = inner, cornerRadius = CornerRadius(3.dp.toPx()))
        }
        if (charging) {
            Icon(painterResource(R.drawable.ic_bolt), null, Modifier.padding(start = 7.dp).size(12.dp), tint = YaeColors.onAccent)
        }
    }
}


/** The time large, then the greeting and the date. */
@Composable
private fun ClockCard(userName: String, large: Boolean, modifier: Modifier) {
    val clock = rememberClock()
    val design = LocalClocks.current.dock
    if (design.svg != null) {
        DockCard(modifier, padding = 16.dp, spacing = 2.dp) {
            // Landscape gives the card its height; portrait gives the clock one by its shape.
            val face = if (large) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(if (design.aspect < 1.4f) 160.dp else 120.dp)
            val palette = YaeColors
            ClockFace(design, remember(palette) { clockColors(palette) }, userName, face, ClockAlign.START)
            Text(
                greeting(clock.hour, userName),
                Modifier.padding(top = 8.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = YaeColors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!design.showsDate) Muted(clock.date, size = 13.sp)
        }
        return
    }
    DockCard(modifier, padding = 16.dp, spacing = if (large) 0.dp else 4.dp) {
        if (large) Fill()
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val size = if (large) 60.sp else 54.sp
            Text(clock.time, fontFamily = Mono, fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, letterSpacing = (-3).sp, maxLines = 1)
            clock.period?.let { Muted(it, Modifier.padding(bottom = 8.dp), size = 15.sp) }
        }
        Text(
            greeting(clock.hour, userName),
            Modifier.padding(top = 6.dp),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = YaeColors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Muted(clock.date, size = 13.sp)
        if (large) Fill()
    }
}

/** Screenshot, record, microphone and the MangoHud overlay, as far as this PC has them. */
@Composable
private fun QuickActions(welcome: Welcome, state: State, record: RecordControl?, send: (ClientMessage) -> Unit, columns: Int, modifier: Modifier) {
    // Inside the grid's card the tiles are raised; on their own they are cards.
    val base = if (columns >= 4) YaeColors.card else YaeColors.raised
    val tiles = buildList<@Composable (Modifier) -> Unit> {
        if (welcome.screenshot) {
            add { ActionTile(R.drawable.ic_camera, "Screenshot", it, base to YaeColors.text) { send(TakeScreenshot) } }
        }
        record?.let { control -> add { RecordTile(control, it, base) } }
        state.mic?.let { mic ->
            add {
                ActionTile(
                    if (mic.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                    if (mic.muted) "Muted" else "Mic",
                    it,
                    colors = if (mic.muted) YaeColors.redSoft to YaeColors.redText else base to YaeColors.text,
                ) { send(SetMic(muted = !mic.muted)) }
            }
        }
        state.overlay?.let { visible ->
            add {
                ActionTile(
                    R.drawable.ic_overlay,
                    if (visible) "Overlay on" else "Overlay off",
                    it,
                    colors = if (visible) YaeColors.accentSoft to YaeColors.accent else base to YaeColors.text,
                ) { send(SetOverlay(!visible)) }
            }
        }
    }
    if (tiles.isEmpty()) return
    if (columns >= tiles.size) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (tile in tiles) tile(Modifier.weight(1f).fillMaxHeight())
        }
        return
    }
    // A grid in a card, each tile raised inside it.
    DockCard(modifier, padding = 10.dp, spacing = 8.dp) {
        for (row in tiles.chunked(columns)) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A big square-ish button: an icon over a word. */
@Composable
private fun ActionTile(
    icon: Int,
    label: String,
    modifier: Modifier,
    colors: Pair<Color, Color> = YaeColors.card to YaeColors.text,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(CardShape).background(colors.first).clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = colors.second)
        Text(label, fontSize = 12.sp, color = colors.second, maxLines = 1)
    }
}

/** Idle: starts a recording. Recording: a red timer that stops it. */
@Composable
private fun RecordTile(record: RecordControl, modifier: Modifier, base: Color) {
    val recording = record.recording?.takeIf { it.active }
    when {
        record.pending -> Box(modifier.clip(CardShape).background(base), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), color = YaeColors.red, strokeWidth = 2.dp)
        }
        recording != null -> ActionTile(
            R.drawable.ic_record,
            formatTimer(recording.durationMs),
            modifier,
            colors = YaeColors.redSoft to YaeColors.redText,
        ) { record.onRecord(false) }
        else -> ActionTile(R.drawable.ic_record, "Record", modifier, base to YaeColors.text) { record.onRecord(true) }
    }
}

/** The quick controls card: volume, brightness, then the audio output and power profile. */
@Composable
private fun QuickControls(state: State, allowVolumeBoost: Boolean, send: (ClientMessage) -> Unit, modifier: Modifier) {
    DockCard(modifier, padding = 10.dp, spacing = 2.dp) {
        Spacer(Modifier.weight(1f))
        state.volume?.let { VolumeSlider(it, allowVolumeBoost, send) }
        state.displays.firstOrNull()?.let { BrightnessSlider(it, send) }
        Spacer(Modifier.weight(1f))
        val chips = state.outputs.isNotEmpty() || state.power != null
        if (chips) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.outputs.isNotEmpty()) OutputChip(state.outputs, send, Modifier.weight(1f))
                state.power?.let { PowerProfileChip(it, send, Modifier.weight(1f)) }
            }
        }
        if (state.displays.isEmpty() && state.volume == null && !chips) {
            Muted("This PC has no brightness or volume control.", size = 13.sp, maxLines = 2)
        }
    }
}

/** `9.4`: gibibytes with one decimal. */
fun gib(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_073_741_824.0)

/** `2.4 MiB` as `2.4` and `MiB`. */
fun splitBytes(bytes: Long): Pair<String, String> {
    val text = formatBytes(bytes)
    return text.substringBefore(' ') to text.substringAfter(' ')
}

/** The CPU package sensor, or failing that a sensor whose name mentions the CPU. */
fun cpuTemperature(stats: Stats): Float? {
    val temps = stats.temps ?: return null
    return temps["cpu_package"] ?: temps.entries.firstOrNull { it.key.startsWith("cpu") || it.key.startsWith("k10temp") }?.value
}

/** `eldenring.exe` → `eldenring`: the program name, as readable as it gets. */
fun gameTitle(name: String): String = name.removeSuffix(".exe").removeSuffix(".x86_64").ifEmpty { "Game" }

fun celsius(value: Float) = String.format(Locale.ROOT, "%.0f°C", value)
