package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.net.PhoneCameraSender

/** What the PC is borrowing from this phone right now, for the tab's dot. */
fun phoneLent(phoneMic: Boolean, phoneCamera: Boolean) = phoneMic || phoneCamera

/**
 * Phone as…: the ways this phone stands in for the PC's hardware. Its screen,
 * microphone, camera, keyboard and mouse, and game controller. Each is a card with its state and its buttons at the foot.
 */
@Composable
fun PhoneScreen(
    pcName: String,
    status: Status,
    wide: Boolean,
    onRetry: () -> Unit,
    onPairAgain: () -> Unit,
    onSettings: () -> Unit,
    /** Opens the PC's screen on this phone, mirrored or as an extra monitor. */
    onScreen: (extend: Boolean) -> Unit,
    phoneMic: Boolean,
    onPhoneMic: (Boolean) -> Unit,
    phoneCamera: Boolean,
    /** Lends the front camera (true) or the back one (false), or takes the camera back (null). */
    onPhoneCamera: (Boolean?) -> Unit,
    cameraHeight: Int,
    onCameraHeight: (Int) -> Unit,
    /** Opens the trackpad and keyboard page. */
    onInput: () -> Unit,
    /** Opens the game controller. */
    onController: () -> Unit,
) {
    val welcome = (status as? Status.Connected)?.welcome
    val header = @Composable {
        ScreenHeader("Use this phone as…", pcName) {
            if (!wide) SquareButton(settingsIcon(), "Settings", onSettings)
        }
    }
    if (welcome == null) {
        Column(Modifier.fillMaxSize()) {
            header()
            StatusPanel(pcName, status, onRetry, onPairAgain)
        }
        return
    }
    val cards = listOf<@Composable (Modifier) -> Unit>(
        { m ->
            ToolCard(
                R.drawable.ic_monitor,
                "Screen",
                if (welcome.screen) "Mirror one of $pcName's screens, or be an extra screen beside them." else "$pcName can't share its screen.",
                m,
            ) {
                PillButton("Mirror", enabled = welcome.screen) { onScreen(false) }
                if (welcome.screenExtend) PillButton("Extend") { onScreen(true) }
            }
        },
        { m ->
            ToolCard(
                R.drawable.ic_mic,
                "Microphone",
                when {
                    !welcome.phoneMic -> "$pcName can't use this phone's microphone."
                    phoneMic -> "$pcName hears this phone as its microphone."
                    else -> "Talk to $pcName through this phone."
                },
                m,
                tag = if (phoneMic) Tag.On else null,
                lent = phoneMic,
            ) {
                if (phoneMic) {
                    PillButton("Stop", colors = YaeColors.red to YaeColors.background) { onPhoneMic(false) }
                } else {
                    PillButton("Start", enabled = welcome.phoneMic) { onPhoneMic(true) }
                }
            }
        },
        { m ->
            ToolCard(
                R.drawable.ic_camera,
                "Camera",
                when {
                    !welcome.phoneCamera -> "$pcName can't use this phone's camera."
                    phoneCamera -> "$pcName sees this phone as a webcam, at ${cameraHeight}p."
                    else -> "A webcam for calls and OBS, at ${cameraHeight}p."
                },
                m,
                tag = if (phoneCamera) Tag.On else Tag.Beta,
                lent = phoneCamera,
            ) {
                if (phoneCamera) {
                    PillButton("Stop", colors = YaeColors.red to YaeColors.background) { onPhoneCamera(null) }
                } else {
                    CameraChoice(enabled = welcome.phoneCamera, onPhoneCamera, cameraHeight, onCameraHeight)
                }
            }
        },
        { m ->
            ToolCard(
                R.drawable.ic_trackpad,
                "Keyboard & mouse",
                if (welcome.input) {
                    "Move $pcName's pointer, click, scroll and type, with Esc, Ctrl and the F-keys."
                } else {
                    "$pcName can't take input yet: run yaedock check on it."
                },
                m,
            ) {
                PillButton("Open", enabled = welcome.input, onClick = onInput)
            }
        },
        { m ->
            ToolCard(
                R.drawable.ic_gamepad,
                "Controller",
                if (welcome.gamepad) {
                    "A game pad that games on $pcName see as a real one."
                } else {
                    "$pcName can't take a controller yet: run yaedock check on it."
                },
                m,
            ) {
                PillButton("Open", enabled = welcome.gamepad, onClick = onController)
            }
        },
    )
    if (wide) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            for (row in cards.chunked(3)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (card in row) card(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            header()
            for (card in cards) card(Modifier.fillMaxWidth().height(136.dp))
        }
    }
}

private enum class Tag(val label: String) { On("On"), Beta("Beta") }

/**
 * A tool: its icon, name and tag in one row, up to two lines about it, and its
 * buttons pinned to the foot. A tool the PC is using right now is outlined in red.
 */
@Composable
private fun ToolCard(
    icon: Int,
    title: String,
    text: String,
    modifier: Modifier,
    tag: Tag? = null,
    lent: Boolean = false,
    dim: Boolean = false,
    buttons: @Composable RowScope.() -> Unit,
) {
    val outline = if (lent) Modifier.background(YaeColors.redSoft) else Modifier.background(YaeColors.card)
    Column(
        modifier.clip(CardShape).then(outline).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (lent) YaeColors.background.copy(alpha = 0.35f) else YaeColors.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon),
                    null,
                    Modifier.size(19.dp),
                    tint = when {
                        lent -> YaeColors.redText
                        dim -> YaeColors.muted
                        else -> YaeColors.text
                    },
                )
            }
            Text(
                title,
                Modifier.weight(1f),
                fontFamily = Grotesk,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (dim) YaeColors.muted else YaeColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            tag?.let { TagPill(it) }
        }
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = YaeColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = buttons)
    }
}

@Composable
private fun TagPill(tag: Tag) {
    val (back, front) = when (tag) {
        Tag.On -> YaeColors.background.copy(alpha = 0.35f) to YaeColors.redText
        Tag.Beta -> YaeColors.amberSoft to YaeColors.amberText
    }
    Text(
        tag.label,
        Modifier.clip(CircleShape).background(back).padding(horizontal = 8.dp, vertical = 2.dp),
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = front,
        maxLines = 1,
    )
}

/** A small rounded button at a card's foot. */
@Composable
private fun PillButton(
    text: String,
    enabled: Boolean = true,
    colors: Pair<Color, Color> = YaeColors.raised to YaeColors.text,
    onClick: () -> Unit,
) {
    ChipButton(
        text,
        onClick,
        colors = if (enabled) colors else Color.Transparent to YaeColors.muted,
        height = 36.dp,
        enabled = enabled,
        fontSize = 12.5.sp,
    )
}

/**
 * Back and Front, then a menu of picture sizes. The menu says first that the
 * camera is in beta, so nobody finds out in the middle of a call.
 */
@Composable
private fun CameraChoice(enabled: Boolean, onPhoneCamera: (Boolean?) -> Unit, height: Int, onHeight: (Int) -> Unit) {
    PillButton("Back", enabled = enabled) { onPhoneCamera(false) }
    PillButton("Front", enabled = enabled) { onPhoneCamera(true) }
    Box {
        var open by remember { mutableStateOf(false) }
        PillButton("${height}p", enabled = enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = YaeColors.raised) {
            Column(Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Beta", color = YaeColors.amberText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "The camera may not work as expected, and can make this phone's microphone glitch while both are on. Sizes above 360p are the least steady.",
                    color = YaeColors.muted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
            HorizontalDivider(color = YaeColors.track)
            for (lines in PhoneCameraSender.HEIGHTS) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${lines}p")
                            if (lines > 360) Text("  may lag", color = YaeColors.amberText, fontSize = 12.sp)
                        }
                    },
                    leadingIcon = { if (lines == height) Icon(Icons.Default.Check, null, Modifier.size(20.dp)) else Box(Modifier.size(20.dp)) },
                    onClick = { onHeight(lines); open = false },
                )
            }
        }
    }
}

