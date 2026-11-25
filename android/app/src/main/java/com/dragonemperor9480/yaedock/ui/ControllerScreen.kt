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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.DpSize
import com.dragonemperor9480.yaedock.ControllerPiece
import com.dragonemperor9480.yaedock.ControllerPlacements
import com.dragonemperor9480.yaedock.Placement
import com.dragonemperor9480.yaedock.placements
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
 * buttons, and View, Menu and Guide in the middle. Edit lets the user move
 * and resize each of them, which becomes the custom layout. Turns the phone sideways
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
    /** Keeps an arrangement made with Edit, as the custom layout. */
    onLayout: (Map<ControllerPiece, Placement>) -> Unit,
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

    // While editing, a copy of the placements that Save keeps and Cancel drops.
    var draft by remember { mutableStateOf<Map<ControllerPiece, Placement>?>(null) }
    var selected by remember { mutableStateOf<ControllerPiece?>(null) }
    val editing = draft != null
    val placements = draft ?: settings.placements()

    // Let go of everything when editing starts, so nothing stays held.
    LaunchedEffect(editing) {
        if (editing) {
            pad.held.keys.toList().forEach { pad.press(it, false) }
            pad.left = Offset.Zero
            pad.right = Offset.Zero
            pad.trigger(right = false, down = false)
            pad.trigger(right = true, down = false)
        }
    }

    BoxWithConstraints(
        modifier.fillMaxSize().background(YaeColors.background).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        // As big as chosen, but never so big that a stick runs into the
        // buttons above it in the usual layouts.
        val fit = (maxHeight - TOP_ROOM) / (FACE + STICK)
        val scale = min(settings.size.scale, fit)
        val width = maxWidth
        val height = maxHeight

        for (piece in ControllerPiece.entries) {
            val at = placements[piece] ?: continue
            val size = piece.baseSize() * (scale * at.scale)
            // Kept whole on the screen, wherever its middle was stored.
            val x = (width * at.x - size.width / 2).coerceIn(0.dp, (width - size.width).coerceAtLeast(0.dp))
            val y = (height * at.y - size.height / 2).coerceIn(0.dp, (height - size.height).coerceAtLeast(0.dp))
            Box(Modifier.offset(x, y).size(size)) {
                Piece(piece, size, pad, press, vibrate)
                if (editing) {
                    val chosen = selected == piece
                    Box(
                        Modifier.matchParentSize()
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (chosen) YaeColors.accent.copy(alpha = 0.18f) else Color.Transparent)
                            .border(if (chosen) 2.dp else 1.dp, if (chosen) YaeColors.accent else YaeColors.muted, RoundedCornerShape(18.dp))
                            .semantics { contentDescription = "Move ${piece.label}" }
                            .pointerInput(piece) {
                                detectTapGestures { selected = piece }
                            }
                            .pointerInput(piece, width, height) {
                                detectDragGestures(onDragStart = { selected = piece }) { change, drag ->
                                    change.consume()
                                    draft = draft?.let { now ->
                                        val was = now[piece] ?: return@let now
                                        now + (piece to was.copy(x = was.x + drag.x / width.toPx(), y = was.y + drag.y / height.toPx()).tidy())
                                    }
                                }
                            },
                    )
                }
            }
        }

        val bar = Modifier.align(Alignment.TopCenter).widthIn(max = 420.dp)
        if (editing) {
            EditBar(
                selected = selected,
                scaleOf = { piece -> placements[piece]?.scale ?: 1f },
                onScale = { piece, step ->
                    draft = draft?.let { now ->
                        val was = now[piece] ?: return@let now
                        now + (piece to was.copy(scale = ((was.scale + step) * 10).roundToInt() / 10f).tidy())
                    }
                },
                onReset = {
                    draft = ControllerPlacements.XBOX
                    selected = null
                },
                onCancel = {
                    draft = null
                    selected = null
                },
                onSave = {
                    val kept = draft
                    if (kept != null) onLayout(kept)
                    draft = null
                    selected = null
                },
                modifier = bar,
            )
        } else {
            Row(bar, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SquareButton(
                    painterResource(R.drawable.ic_chevron_left),
                    "Back",
                    onBack,
                    Modifier.size(34.dp).clip(CircleShape),
                    background = YaeColors.raised,
                )
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
                ChipButton(
                    "Edit",
                    { draft = placements },
                    Modifier.height(34.dp).clip(CircleShape),
                    height = 34.dp,
                    fontSize = 12.5.sp,
                )
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
        }
    }
}

/** Room kept along the top for the bar and the shoulder buttons. */
private val TOP_ROOM = 70.dp

/** How big [this] is at Medium, before its own size. */
private fun ControllerPiece.baseSize(): DpSize = when (this) {
    ControllerPiece.LEFT_STICK, ControllerPiece.RIGHT_STICK -> DpSize(STICK, STICK)
    ControllerPiece.DPAD -> DpSize(DPAD, DPAD)
    ControllerPiece.FACE -> DpSize(FACE, FACE)
    ControllerPiece.LT, ControllerPiece.LB, ControllerPiece.RB, ControllerPiece.RT -> DpSize(76.dp, 46.dp)
    ControllerPiece.VIEW, ControllerPiece.MENU -> DpSize(46.dp, 30.dp)
    ControllerPiece.GUIDE -> DpSize(58.dp, 58.dp)
}

/** One piece of the controller, filling [size]. */
@Composable
private fun Piece(piece: ControllerPiece, size: DpSize, pad: Pad, press: (GamepadButton, Boolean) -> Unit, vibrate: Boolean) {
    val fill = Modifier.fillMaxSize()
    when (piece) {
        ControllerPiece.LEFT_STICK -> Stick(size.width, "Left stick", Modifier) { pad.left = it }
        ControllerPiece.RIGHT_STICK -> Stick(size.width, "Right stick", Modifier) { pad.right = it }
        ControllerPiece.DPAD -> DPad(size.width, pad, press, Modifier)
        ControllerPiece.FACE -> FaceButtons(size.width, pad, press, Modifier)
        ControllerPiece.LT -> Shoulder("LT", fill, pad.lt > 0f, vibrate) { pad.trigger(right = false, down = it) }
        ControllerPiece.RT -> Shoulder("RT", fill, pad.rt > 0f, vibrate) { pad.trigger(right = true, down = it) }
        ControllerPiece.LB -> Shoulder("LB", fill, pad.held[GamepadButton.LB] == true, vibrate) { press(GamepadButton.LB, it) }
        ControllerPiece.RB -> Shoulder("RB", fill, pad.held[GamepadButton.RB] == true, vibrate) { press(GamepadButton.RB, it) }
        ControllerPiece.VIEW -> MiddleButton(R.drawable.ic_view, "View", GamepadButton.VIEW, pad, press)
        ControllerPiece.MENU -> MiddleButton(R.drawable.ic_menu, "Menu", GamepadButton.MENU, pad, press)
        ControllerPiece.GUIDE -> {
            val guide = pad.held[GamepadButton.GUIDE] == true
            Box(
                fill.clip(CircleShape)
                    .background(if (guide) YaeColors.accent else YaeColors.accentSoft)
                    .held(GamepadButton.GUIDE, "Guide", press),
                contentAlignment = Alignment.Center,
            ) {
                Text("Guide", fontFamily = Grotesk, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (guide) YaeColors.onAccent else YaeColors.accent)
            }
        }
    }
}

/** Along the top while editing: what to do, the chosen piece's size, and Save. */
@Composable
private fun EditBar(
    selected: ControllerPiece?,
    scaleOf: (ControllerPiece) -> Float,
    onScale: (ControllerPiece, Float) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(YaeColors.card).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val chip = Modifier.height(34.dp).clip(CircleShape)
        ChipButton("Cancel", onCancel, chip, height = 34.dp, fontSize = 12.5.sp)
        if (selected == null) {
            Text(
                "Drag to move · tap to resize",
                Modifier.weight(1f),
                fontSize = 12.sp,
                color = YaeColors.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            val scale = scaleOf(selected)
            ChipButton("−", { onScale(selected, -Placement.SCALE_STEP) }, chip.widthIn(min = 48.dp), height = 34.dp, fontSize = 16.sp, enabled = scale > Placement.MIN_SCALE + 0.01f)
            Text(
                "${selected.label} ${(scale * 100).roundToInt()}%",
                Modifier.weight(1f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ChipButton("+", { onScale(selected, Placement.SCALE_STEP) }, chip.widthIn(min = 48.dp), height = 34.dp, fontSize = 16.sp, enabled = scale < Placement.MAX_SCALE - 0.01f)
        }
        ChipButton("Reset", onReset, chip, height = 34.dp, fontSize = 12.5.sp)
        ChipButton("Save", onSave, chip, selected = true, height = 34.dp, fontSize = 12.5.sp)
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
        modifier.clip(RoundedCornerShape(16.dp))
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
        Modifier.fillMaxSize().clip(CircleShape)
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
