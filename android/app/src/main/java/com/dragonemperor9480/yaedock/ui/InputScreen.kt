package com.dragonemperor9480.yaedock.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.Key
import com.dragonemperor9480.yaedock.protocol.KeyPress
import com.dragonemperor9480.yaedock.protocol.MouseButton
import com.dragonemperor9480.yaedock.protocol.PointerButton
import com.dragonemperor9480.yaedock.protocol.PointerMove
import com.dragonemperor9480.yaedock.protocol.PointerScroll
import com.dragonemperor9480.yaedock.protocol.TypeText
import com.dragonemperor9480.yaedock.ui.TrackpadGestures.Action

/** Keeps the hidden text field from ever being empty, so Backspace always changes it. */
private const val SENTINEL = " "

/** The text the field goes back to; the cursor sits after the sentinel. */
private val EmptyField = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))

/** Keys that stay held until the next key or letter, as on a phone's Shift. */
private val Modifiers = listOf(Key.CTRL to "Ctrl", Key.ALT to "Alt", Key.SHIFT to "Shift", Key.SUPER to "Super")

/** Keys phones lack, after the modifiers. */
private val Keys = listOf(
    Key.ESC to "Esc", Key.TAB to "Tab",
    Key.LEFT to "←", Key.UP to "↑", Key.DOWN to "↓", Key.RIGHT to "→",
    Key.HOME to "Home", Key.END to "End", Key.PAGE_UP to "PgUp", Key.PAGE_DOWN to "PgDn",
    Key.DELETE to "Del", Key.INSERT to "Ins",
    Key.F1 to "F1", Key.F2 to "F2", Key.F3 to "F3", Key.F4 to "F4", Key.F5 to "F5", Key.F6 to "F6",
    Key.F7 to "F7", Key.F8 to "F8", Key.F9 to "F9", Key.F10 to "F10", Key.F11 to "F11", Key.F12 to "F12",
)

/**
 * The phone as the PC's keyboard and mouse: a trackpad filling the page, the
 * mouse's buttons below it, a row of keys phones lack, and the phone's own
 * keyboard for typing. Whatever is held is let go when the page closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputScreen(
    pcName: String,
    connected: Boolean,
    send: (ClientMessage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sendNow by rememberUpdatedState(send)
    val density = LocalDensity.current.density
    val gestures = remember(density) { TrackpadGestures(density) }
    val pending = remember { PointerAccumulator() }
    var held by remember { mutableStateOf(emptySet<Key>()) }

    fun click(button: MouseButton) {
        sendNow(PointerButton(button, true))
        sendNow(PointerButton(button, false))
    }
    fun dispatch(actions: List<Action>) {
        for (action in actions) when (action) {
            is Action.Move -> pending.addMove(action.dx, action.dy)
            is Action.Scroll -> pending.addScroll(action.dx, action.dy)
            is Action.Click -> click(action.button)
            is Action.Press -> sendNow(PointerButton(action.button, action.down))
        }
    }
    fun letGoOfModifiers() {
        for (key in held) sendNow(KeyPress(key, false))
        held = emptySet()
    }
    fun press(key: Key) {
        sendNow(KeyPress(key, true))
        sendNow(KeyPress(key, false))
        letGoOfModifiers()
    }
    fun toggle(key: Key) {
        held = if (key in held) {
            sendNow(KeyPress(key, false))
            held - key
        } else {
            sendNow(KeyPress(key, true))
            held + key
        }
    }

    // Moves and scrolls go once a frame, and a tap's click once no second touch came.
    LaunchedEffect(gestures) {
        while (true) {
            withFrameMillis { }
            dispatch(gestures.tick(SystemClock.uptimeMillis()))
            pending.takeMove()?.let { (dx, dy) -> sendNow(PointerMove(dx, dy)) }
            pending.takeScroll()?.let { (dx, dy) -> sendNow(PointerScroll(dx, dy)) }
        }
    }

    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            dispatch(gestures.cancel())
            letGoOfModifiers()
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(EmptyField) }
    val typing = WindowInsets.isImeVisible

    Column(
        modifier.fillMaxSize().background(YaeColors.background).imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader(
            "Keyboard & mouse",
            if (connected) pcName else "Waiting for $pcName…",
            leading = { BackButton(onBack) },
        ) {
            SquareButton(
                painterResource(R.drawable.ic_keyboard),
                if (typing) "Hide the keyboard" else "Type",
                onClick = {
                    if (typing) {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    } else {
                        focus.requestFocus()
                        keyboard?.show()
                    }
                },
                background = if (typing) YaeColors.accentSoft else YaeColors.card,
                tint = if (typing) YaeColors.accent else YaeColors.text,
            )
        }

        // Typing goes through a field nobody sees: what changes in it goes to the PC.
        BasicTextField(
            value = field,
            onValueChange = { new ->
                val (erase, type) = textChange(field.text, new.text)
                if (erase > 0 || type.isNotEmpty()) {
                    repeat(erase) {
                        sendNow(KeyPress(Key.BACKSPACE, true))
                        sendNow(KeyPress(Key.BACKSPACE, false))
                    }
                    if (type.isNotEmpty()) sendNow(TypeText(type))
                    letGoOfModifiers()
                }
                // Start afresh once a word is done, or when the sentinel went, so the field stays short.
                val settled = new.composition == null
                field = when {
                    !new.text.startsWith(SENTINEL) -> EmptyField
                    settled && new.text.length > 40 -> EmptyField
                    settled -> new.copy(selection = TextRange(new.text.length))
                    else -> new
                }
            },
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None,
            ),
        )

        Box(
            Modifier.weight(1f).fillMaxWidth().clip(CardShape).background(YaeColors.card)
                .semantics { contentDescription = "Trackpad" }
                .pointerInput(gestures) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val id = change.id.value
                                val (x, y) = change.position
                                val time = change.uptimeMillis
                                when {
                                    change.changedToDown() -> dispatch(gestures.down(id, x, y, time))
                                    change.changedToUp() -> dispatch(gestures.up(id, time))
                                    change.pressed && change.positionChanged() -> dispatch(gestures.move(id, x, y, time))
                                }
                                change.consume()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Move with one finger · tap to click\nTwo fingers to scroll · tap with two to right-click\nTap, then hold and move to drag",
                Modifier.widthIn(max = 360.dp).padding(16.dp),
                color = YaeColors.muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((key, label) in Modifiers) {
                KeyChip(label, lit = key in held) { toggle(key) }
            }
            for ((key, label) in Keys) {
                KeyChip(label) { press(key) }
            }
        }

        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MouseButtonPad("Left click", Modifier.weight(1f)) { down -> sendNow(PointerButton(MouseButton.LEFT, down)) }
            MouseButtonPad("Right click", Modifier.weight(1f)) { down -> sendNow(PointerButton(MouseButton.RIGHT, down)) }
        }
    }
}

/** One key in the row; a held modifier is lit. */
@Composable
private fun KeyChip(label: String, lit: Boolean = false, onClick: () -> Unit) {
    ChipButton(
        label,
        onClick,
        Modifier.widthIn(min = 48.dp),
        selected = lit,
        colors = if (lit) YaeColors.accentSoft to YaeColors.accent else YaeColors.card to YaeColors.text,
        height = 40.dp,
        fontSize = 13.sp,
    )
}

/** A mouse button: pressed while the finger is down, so it can also hold for a drag. */
@Composable
private fun MouseButtonPad(label: String, modifier: Modifier, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val pressNow by rememberUpdatedState(onPress)
    Box(
        modifier.fillMaxSize().clip(ButtonShape)
            .background(if (down) YaeColors.accentSoft else YaeColors.card)
            .semantics { role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        down = true
                        pressNow(true)
                        tryAwaitRelease()
                        pressNow(false)
                        down = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (down) YaeColors.accent else YaeColors.text)
    }
}
