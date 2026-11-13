package com.dragonemperor9480.yaedock.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.net.ScreenDecoder
import com.dragonemperor9480.yaedock.protocol.ScreenMode
import com.dragonemperor9480.yaedock.protocol.ScreenState
import com.dragonemperor9480.yaedock.protocol.StartScreen
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The PC's screen, live: one of its monitors, or with [extend] a display of
 * the phone's own beside them. It runs only while this is on screen: it
 * starts when the screen shows and stops when the app leaves it.
 * [maxHeight] is the most lines the picture has, 0 for all there are.
 */
@Composable
fun MirrorScreen(
    connection: DaemonConnection,
    connected: Boolean,
    pcName: String,
    canExtend: Boolean,
    extend: Boolean,
    maxHeight: Int,
    onExtend: (Boolean) -> Unit,
    onMaxHeight: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    val mirror by connection.screen.collectAsStateWithLifecycle()
    // Counted up to ask again after a refusal.
    var attempt by remember { mutableStateOf(0) }

    // The picture has the whole screen: the status and navigation bars go, and
    // come back for a moment with a swipe from the edge.
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = view.context.activity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // A display of the phone's own has the shape of the screen. The window's
    // size is used, which is the same before the bars have gone as after.
    val request = if (extend) {
        val window = view.rootView
        val (spaceWidth, spaceHeight) = when {
            window.width > 0 && window.height > 0 -> window.width to window.height
            else -> constraints.maxWidth to constraints.maxHeight
        }
        val short = min(spaceWidth, spaceHeight)
        val scale = if (maxHeight in 1 until short) maxHeight.toFloat() / short else 1f
        StartScreen(ScreenMode.EXTEND, (spaceWidth * scale).roundToInt(), (spaceHeight * scale).roundToInt())
    } else {
        StartScreen(height = maxHeight.takeIf { it > 0 })
    }
    LifecycleStartEffect(connection, connected, attempt, request) {
        if (connected) connection.startScreen(request)
        onStopOrDispose { connection.stopScreen() }
    }

    val on = mirror?.takeIf { it.state == ScreenState.ON }
    // The PC says the size when it knows.
    val width = on?.width ?: 1920
    val height = on?.height ?: 1080
    val size by rememberUpdatedState(width to height)

    run {
        if (on != null) {
            // A TextureView, not a SurfaceView: on some phones a SurfaceView put
            // on screen this way now and then never gets its surface, and stays black.
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        keepScreenOn = true
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            private var decoder: ScreenDecoder? = null

                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                val target = Surface(texture).also { surface = it }
                                // The PC's size, not the view's; a decoder told wrong still follows the stream.
                                decoder = ScreenDecoder(target, size.first, size.second).also { connection.videoSink = it::feed }
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                connection.videoSink = null
                                decoder?.release()
                                decoder = null
                                surface?.release()
                                surface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.aspectRatio(width.toFloat() / height),
            )
        } else {
            val (title, detail) = when {
                !connected -> "Not connected" to "The picture comes back when $pcName is connected again."
                mirror?.state == ScreenState.OFF -> "Stopped" to (mirror?.message ?: "The screen is no longer shared.")
                mirror?.state == ScreenState.ASKING && extend ->
                    "Allow it on your PC" to "$pcName is asking whether to add this phone as a display. Choose Share there."
                mirror?.state == ScreenState.ASKING -> "Allow it on your PC" to "$pcName is asking which screen to share. Pick one there and choose Share."
                else -> "Starting…" to "Asking $pcName for its screen."
            }
            Column(
                Modifier.widthIn(max = 420.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
                if (connected && mirror?.state == ScreenState.OFF) ChipButton("Try again", { attempt++ })
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(12.dp)) { BackButton(onBack) }
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) { ScreenMenu(canExtend, extend, maxHeight, onExtend, onMaxHeight) }
    }
}

/** What to show of the PC, and how large a picture to ask for. */
@Composable
private fun ScreenMenu(canExtend: Boolean, extend: Boolean, maxHeight: Int, onExtend: (Boolean) -> Unit, onMaxHeight: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }

    @Composable
    fun Choice(text: String, chosen: Boolean, choose: () -> Unit) = DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { if (chosen) Icon(Icons.Default.Check, null, Modifier.size(20.dp)) else Box(Modifier.size(20.dp)) },
        onClick = { open = false; choose() },
    )
    SquareButton(settingsIcon(), "Screen options", { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = YaeColors.raised) {
        if (canExtend) {
            Choice("Mirror a screen", !extend) { onExtend(false) }
            Choice("Second display", extend) { onExtend(true) }
            HorizontalDivider(color = YaeColors.track)
        }
        // Fewer lines are lighter on Wi-Fi, and make a second display's contents larger.
        for ((label, lines) in PICTURE_SIZES) Choice(label, maxHeight == lines) { onMaxHeight(lines) }
    }
}

private val PICTURE_SIZES = listOf("Full size" to 0, "1080p" to 1080, "720p" to 720, "540p" to 540)

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
