package com.dragonemperor9480.yaedock.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dragonemperor9480.yaedock.DockSettings
import com.dragonemperor9480.yaedock.KeepScreenOn
import kotlin.random.Random
import kotlinx.coroutines.delay

/** How often the dashboard moves to spread OLED wear, and how far it may move. */
private const val SHIFT_INTERVAL_MS = 3 * 60_000L
private val MAX_SHIFT = 4.dp

/** Window brightness while dimmed, as a fraction of full brightness. */
private const val DIM_BRIGHTNESS = 0.02f

/**
 * Wraps the dashboard with the dock behaviour from [settings]: keeping the
 * screen on, hiding the system bars in landscape, dimming after a while without
 * a touch, and shifting the layout a little to protect an OLED screen.
 */
@Composable
fun DockMode(window: Window, settings: DockSettings, content: @Composable () -> Unit) {
    val charging = rememberCharging()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val view = LocalView.current

    val keepOn = when (settings.keepScreenOn) {
        KeepScreenOn.ALWAYS -> true
        KeepScreenOn.WHILE_CHARGING -> charging
        KeepScreenOn.NEVER -> false
    }
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    val fullScreen = settings.fullScreen && landscape
    DisposableEffect(fullScreen) {
        val controller = WindowCompat.getInsetsController(window, view)
        if (fullScreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Dimming only matters while the screen is kept on; otherwise it turns off as usual.
    var lastTouch by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    val canDim = keepOn && settings.dim
    LaunchedEffect(canDim, lastTouch, settings.dimAfterMinutes) {
        dimmed = false
        if (canDim) {
            delay(settings.dimAfterMinutes * 60_000L)
            dimmed = true
        }
    }
    DisposableEffect(dimmed) {
        setWindowBrightness(window, if (dimmed) DIM_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        onDispose { setWindowBrightness(window, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }

    var shift by remember { mutableStateOf(IntOffset.Zero) }
    val maxShift = with(LocalDensity.current) { MAX_SHIFT.roundToPx() }
    LaunchedEffect(settings.burnInProtection) {
        shift = IntOffset.Zero
        while (settings.burnInProtection) {
            delay(SHIFT_INTERVAL_MS)
            shift = IntOffset(Random.nextInt(-maxShift, maxShift + 1), Random.nextInt(-maxShift, maxShift + 1))
        }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Watch every touch without taking it, to know when the user was last here.
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch = SystemClock.uptimeMillis()
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().offset { shift }) { content() }
        if (dimmed) {
            // Darkens the dashboard and swallows the touch that wakes it, so
            // that touch cannot move a slider by accident.
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                },
            )
        }
    }
}

private fun setWindowBrightness(window: Window, brightness: Float) {
    window.attributes = window.attributes.apply { screenBrightness = brightness }
}

/** Whether the phone is plugged in, updated as it changes. */
@Composable
private fun rememberCharging(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            }
        }
        // The battery broadcast is sticky, so registering returns the current state at once.
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )?.let { receiver.onReceive(context, it) }
        onDispose { context.unregisterReceiver(receiver) }
    }
    return charging
}
