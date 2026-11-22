package com.dragonemperor9480.yaedock.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dragonemperor9480.yaedock.ControllerLayout
import com.dragonemperor9480.yaedock.ControllerSettings
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.GamepadButton
import com.dragonemperor9480.yaedock.protocol.GamepadOff
import com.dragonemperor9480.yaedock.protocol.GamepadRumble
import com.dragonemperor9480.yaedock.protocol.GamepadState
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The parts' sizes at Medium, from the design. */
private val STICK = 132.dp
private val FACE = 170.dp
private val DPAD = 136.dp

/** The least room kept above and below the parts. */
private val EDGE = 4.dp

/** The face buttons' letters, in the colours an Xbox pad has. */
private val FaceAmber = Color(0xFFF4B860)
private val FaceBlue = Color(0xFF7CB8FF)
private val FaceRed = Color(0xFFFF8A80)

/**
 * What the phone's controller holds right now; read once a frame and sent
 * when it changed. A press let go within the same frame still goes, held for
 * that one frame, so no tap is lost.
 */
private class Pad {
    val held = mutableStateMapOf<GamepadButton, Boolean>()
    var left by mutableStateOf(Offset.Zero)
    var right by mutableStateOf(Offset.Zero)
    var lt by mutableFloatStateOf(0f)
    var rt by mutableFloatStateOf(0f)

    /** Pressed since the last frame went, let go or not. */
    private val pressed = mutableSetOf<GamepadButton>()
    private var ltPressed = false
    private var rtPressed = false

    fun press(button: GamepadButton, down: Boolean) {
        if (down) pressed += button
        held[button] = down
    }

    fun trigger(right: Boolean, down: Boolean) {
        if (right) {
            if (down) rtPressed = true
            rt = if (down) 1f else 0f
        } else {
            if (down) ltPressed = true
            lt = if (down) 1f else 0f
        }
    }

    /** The controller as it goes this frame; what was pressed since is let go of next frame. */
    fun take(): GamepadState {
        val state = GamepadState(
            buttons = GamepadButton.entries.filter { held[it] == true || it in pressed },
            lx = left.x,
            ly = left.y,
            rx = right.x,
            ry = right.y,
            lt = if (ltPressed) 1f else lt,
            rt = if (rtPressed) 1f else rt,
        )
        pressed.clear()
        ltPressed = false
        rtPressed = false
        return state
    }
}

/**
 * The phone as the PC's game controller, which games see as an Xbox-style
 * pad: triggers and bumpers along the top, two sticks, a D-pad, the face
 * buttons, and View, Menu and Guide in the middle. Turns the phone sideways
 * and takes the whole screen while open; on closing, the PC lets go of
 * everything and removes the controller.
 */
@Composable
fun ControllerScreen(
    pcName: String,
    /** How the phone reaches the PC, such as "cable"; null while not connected. */
    via: String?,
    settings: ControllerSettings,
    onVibration: (Boolean) -> Unit,
    rumbles: Flow<GamepadRumble>,
    send: (ClientMessage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sendNow by rememberUpdatedState(send)
    val pad = remember { Pad() }
    val view = LocalView.current
    val vibrate by rememberUpdatedState(settings.vibration)

    // A light tap under the finger for each press, as the buttons have no travel.
    val press = { button: GamepadButton, down: Boolean ->
        if (down && pad.held[button] != true && vibrate) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        pad.press(button, down)
    }

    // Sideways and the whole screen, with the screen kept on, while it is open.
    DisposableEffect(view) {
        val activity = view.context.activity()
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (before != null) activity.requestedOrientation = before
            sendNow(GamepadOff)
        }
    }

    // The whole controller goes once a frame when it changed; the first one
    // makes the controller on the PC, so games find it before a button is pressed.
    LaunchedEffect(pad) {
        var sent: GamepadState? = null
        while (true) {
            withFrameMillis { }
            val now = pad.take()
            if (now != sent) {
                sendNow(now)
                sent = now
            }
        }
    }

    // A game's rumble, while vibration is on.
    val context = LocalContext.current
    val vibrator = remember(context) { context.vibrator() }
    LaunchedEffect(vibrator, rumbles, settings.vibration) {
        vibrator?.cancel()
        if (vibrator == null || !settings.vibration) return@LaunchedEffect
        rumbles.collect { rumble ->
            val level = max(rumble.strong, rumble.weak)
            if (level < 0.01f) {
                vibrator.cancel()
            } else {
                // Until the next one, with a limit in case the stop never comes.
                val ms = if (rumble.ms > 0) rumble.ms.toLong() else 10_000L
                val amplitude = if (vibrator.hasAmplitudeControl()) {
                    (level * 255).roundToInt().coerceIn(1, 255)
                } else {
                    VibrationEffect.DEFAULT_AMPLITUDE
                }
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            }
        }
    }
    DisposableEffect(vibrator) { onDispose { vibrator?.cancel() } }

    Column(
        modifier.fillMaxSize().background(YaeColors.background).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        // Triggers and bumpers at the edges, as on a pad held in two hands.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.width(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shoulder("LT", Modifier.weight(1f), pad.lt > 0f, vibrate) { pad.trigger(right = false, down = it) }
                Shoulder("LB", Modifier.weight(1f), pad.held[GamepadButton.LB] == true, vibrate) { press(GamepadButton.LB, it) }
            }
            Box(Modifier.width(140.dp)) {
                SquareButton(
                    painterResource(R.drawable.ic_chevron_left),
                    "Back",
                    onBack,
                    Modifier.size(34.dp).clip(CircleShape),
                    background = YaeColors.raised,
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Controller", fontFamily = Grotesk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (via != null) "on $pcName · $via" else "waiting for $pcName…",
                    fontSize = 12.sp,
                    color = YaeColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.width(140.dp), contentAlignment = Alignment.CenterEnd) {
                ChipButton(
                    "Vibration",
                    { onVibration(!settings.vibration) },
                    Modifier.height(34.dp).clip(CircleShape),
                    selected = settings.vibration,
                    height = 34.dp,
                    fontSize = 12.5.sp,
                    colors = if (settings.vibration) YaeColors.accentSoft to YaeColors.accent else YaeColors.raised to YaeColors.text,
                )
            }
            Row(Modifier.width(160.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shoulder("RB", Modifier.weight(1f), pad.held[GamepadButton.RB] == true, vibrate) { press(GamepadButton.RB, it) }
                Shoulder("RT", Modifier.weight(1f), pad.rt > 0f, vibrate) { pad.trigger(right = true, down = it) }
            }
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // As big as chosen, but never so big that a stick runs into the
            // buttons above it: the right side, face buttons over a stick, is the taller.
            val fit = (maxHeight - EDGE * 2) / (FACE + STICK)
            val scale = min(settings.size.scale, fit)
            val stickSize = STICK * scale
            val faceSize = FACE * scale
            val dPadSize = DPAD * scale
            // What room is left goes evenly above, between and below.
            val slack = { top: Dp -> ((maxHeight - top - stickSize) / 3).coerceAtLeast(EDGE) }
            val leftSlack = slack(dPadSize)
            val rightSlack = slack(faceSize)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                val leftStick = @Composable { m: Modifier -> Stick(stickSize, "Left stick", m) { pad.left = it } }
                val dPad = @Composable { m: Modifier -> DPad(dPadSize, pad, press, m) }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (settings.layout) {
                        ControllerLayout.XBOX -> {
                            leftStick(Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = leftSlack))
                            dPad(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = leftSlack))
                        }
                        ControllerLayout.SYMMETRIC -> {
                            dPad(Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = leftSlack))
                            leftStick(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = leftSlack))
                        }
                    }
                }

                Column(Modifier.width(200.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MiddleButton(R.drawable.ic_view, "View", GamepadButton.VIEW, pad, press)
                        MiddleButton(R.drawable.ic_menu, "Menu", GamepadButton.MENU, pad, press)
                    }
                    val guide = pad.held[GamepadButton.GUIDE] == true
                    Box(
                        Modifier.size(58.dp).clip(CircleShape)
                            .background(if (guide) YaeColors.accent else YaeColors.accentSoft)
                            .held(GamepadButton.GUIDE, "Guide", press),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Guide", fontFamily = Grotesk, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (guide) YaeColors.onAccent else YaeColors.accent)
                    }
                    Text(
                        "Games see an Xbox-style pad.\nLayout and size in Settings.",
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = YaeColors.muted,
                        textAlign = TextAlign.Center,
                    )
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    FaceButtons(faceSize, pad, press, Modifier.align(Alignment.TopEnd).padding(end = 40.dp, top = rightSlack))
                    Stick(stickSize, "Right stick", Modifier.align(Alignment.BottomStart).padding(start = 36.dp, bottom = rightSlack)) { pad.right = it }
                }
            }
        }
    }
}

/** Holds [button] while a finger is on it; sliding off lets go. */
private fun Modifier.held(button: GamepadButton, label: String, press: (GamepadButton, Boolean) -> Unit): Modifier =
    held(label) { press(button, it) }

private fun Modifier.held(label: String, onHeld: (Boolean) -> Unit): Modifier = this
    .semantics {
        role = Role.Button
        contentDescription = label
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            onHeld(true)
            try {
                waitForUpOrCancellation()?.consume()
            } finally {
                onHeld(false)
            }
        }
    }

/** A trigger or bumper along the top edge. */
@Composable
private fun Shoulder(label: String, modifier: Modifier, down: Boolean, vibrate: Boolean, onHeld: (Boolean) -> Unit) {
    val view = LocalView.current
    val hold by rememberUpdatedState(onHeld)
    val tick by rememberUpdatedState(vibrate)
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(16.dp))
            .background(if (down) YaeColors.accentSoft else YaeColors.raised)
            .held(label) {
                if (it && tick) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hold(it)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Grotesk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (down) YaeColors.accent else YaeColors.text)
    }
}

/** View or Menu, small pills between the sticks. */
@Composable
private fun MiddleButton(icon: Int, label: String, button: GamepadButton, pad: Pad, press: (GamepadButton, Boolean) -> Unit) {
    val down = pad.held[button] == true
    Box(
        Modifier.size(46.dp, 30.dp).clip(CircleShape)
            .background(if (down) YaeColors.accentSoft else YaeColors.raised)
            .held(button, label, press),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(16.dp), tint = if (down) YaeColors.accent else YaeColors.text)
    }
}

/** The D-pad: four buttons in a cross. */
@Composable
private fun DPad(size: Dp, pad: Pad, press: (GamepadButton, Boolean) -> Unit, modifier: Modifier) {
    val key = size * 44 / 136
    Box(modifier.size(size)) {
        val arrow = @Composable { button: GamepadButton, label: String, icon: Int, at: Alignment ->
            val down = pad.held[button] == true
            Box(
                Modifier.align(at).size(key).clip(RoundedCornerShape(10.dp))
                    .background(if (down) YaeColors.accentSoft else YaeColors.raised)
                    .held(button, label, press),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, Modifier.size(14.dp), tint = if (down) YaeColors.accent else YaeColors.text)
            }
        }
        arrow(GamepadButton.UP, "Up", R.drawable.ic_triangle_up, Alignment.TopCenter)
        arrow(GamepadButton.LEFT, "Left", R.drawable.ic_triangle_left, Alignment.CenterStart)
        arrow(GamepadButton.RIGHT, "Right", R.drawable.ic_triangle_right, Alignment.CenterEnd)
        arrow(GamepadButton.DOWN, "Down", R.drawable.ic_triangle_down, Alignment.BottomCenter)
    }
}

/** A, B, X and Y in a diamond, each letter in its colour. */
@Composable
private fun FaceButtons(size: Dp, pad: Pad, press: (GamepadButton, Boolean) -> Unit, modifier: Modifier) {
    val button = size * 56 / 170
    Box(modifier.size(size)) {
        val face = @Composable { which: GamepadButton, label: String, color: Color, at: Alignment ->
            val down = pad.held[which] == true
            Box(
                Modifier.align(at).size(button).clip(CircleShape)
                    .background(if (down) color.copy(alpha = 0.3f) else YaeColors.raised)
                    .held(which, label, press),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontFamily = Grotesk, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
        face(GamepadButton.Y, "Y", FaceAmber, Alignment.TopCenter)
        face(GamepadButton.X, "X", FaceBlue, Alignment.CenterStart)
        face(GamepadButton.B, "B", FaceRed, Alignment.CenterEnd)
        face(GamepadButton.A, "A", YaeColors.accent, Alignment.BottomCenter)
    }
}

/**
 * An analog stick: the knob follows the finger within the ring, and springs
 * back to the middle when let go. [onMove] gets -1 to 1 on each axis, up and
 * left negative.
 */
@Composable
private fun Stick(size: Dp, label: String, modifier: Modifier, onMove: (Offset) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val move by rememberUpdatedState(onMove)
    val knobSize = size * 62 / 132
    Box(
        modifier.size(size).clip(CircleShape).background(YaeColors.card)
            .border(1.dp, YaeColors.raised, CircleShape)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                val radius = this.size.width / 2f
                val centre = Offset(radius, this.size.height / 2f)
                fun follow(position: Offset) {
                    var value = (position - centre) / radius
                    val length = value.getDistance()
                    if (length > 1f) value /= length
                    knob = value
                    move(value)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    follow(down.position)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            follow(change.position)
                            change.consume()
                        }
                    } finally {
                        knob = Offset.Zero
                        move(Offset.Zero)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val travel = (size - knobSize) / 2
        Spacer(
            Modifier
                .offset { IntOffset((knob.x * travel.toPx()).roundToInt(), (knob.y * travel.toPx()).roundToInt()) }
                .size(knobSize)
                .shadow(6.dp, CircleShape)
                .background(YaeColors.track, CircleShape),
        )
    }
}

/** The phone's vibration motor, if it has one. */
private fun Context.vibrator(): Vibrator? {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }
    return vibrator?.takeIf { it.hasVibrator() }
}
