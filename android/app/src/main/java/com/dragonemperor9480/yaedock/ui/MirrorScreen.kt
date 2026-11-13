package com.dragonemperor9480.yaedock.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.net.ScreenDecoder
import com.dragonemperor9480.yaedock.protocol.ScreenState

/**
 * The PC's screen, live. Mirroring runs only while this is on screen: it
 * starts when the screen shows and stops when the app leaves it.
 */
@Composable
fun MirrorScreen(connection: DaemonConnection, connected: Boolean, pcName: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val mirror by connection.screen.collectAsStateWithLifecycle()
    // Counted up to ask again after a refusal.
    var attempt by remember { mutableStateOf(0) }

    LifecycleStartEffect(connection, connected, attempt) {
        if (connected) connection.startScreen()
        onStopOrDispose { connection.stopScreen() }
    }

    val on = mirror?.takeIf { it.state == ScreenState.ON }
    // The PC says the size when it knows.
    val width = on?.width ?: 1920
    val height = on?.height ?: 1080
    val size by rememberUpdatedState(width to height)

    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
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
                mirror?.state == ScreenState.OFF -> "Mirroring stopped" to (mirror?.message ?: "The screen is no longer shared.")
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
    }
}
