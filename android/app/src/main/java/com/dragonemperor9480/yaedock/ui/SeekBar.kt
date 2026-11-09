package com.dragonemperor9480.yaedock.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.protocol.Media
import kotlinx.coroutines.delay

/**
 * How the media seek bar looks. The defaults are the dock's own; settings can
 * provide another through [LocalSeekBarStyle] without touching the media card.
 */
@Immutable
data class SeekBarStyle(
    /** Height of the track line. */
    val trackHeight: Dp = 4.dp,
    /** Diameter of the thumb; 0 for none (the bar still seeks on tap and drag). */
    val thumbSize: Dp = 12.dp,
    /** Played part of the track; [Color.Unspecified] for the theme's accent. */
    val color: Color = Color.Unspecified,
    /** The elapsed and total times beside the bar. */
    val showTimes: Boolean = true,
    /** The time left (`-1:12`) instead of the total length. */
    val showRemaining: Boolean = false,
)

val LocalSeekBarStyle = staticCompositionLocalOf { SeekBarStyle() }

/** The position moves on as the time shown does, once a second. */
private const val TICK_MS = 1_000L

/** How long a seek shows before the PC confirms it. */
private const val SEEK_HOLD_MS = 3_000L

/**
 * Where [media] is now: its position when it was sent, plus [elapsedMs] since
 * when it plays, within the track's length. Null when the player gives none.
 */
fun playedMs(media: Media, elapsedMs: Long): Long? {
    val base = media.positionMs ?: return null
    val now = if (media.playing) base + elapsedMs.coerceAtLeast(0) else base
    return media.lengthMs?.let { now.coerceIn(0, it) } ?: now
}

/**
 * The track's progress, which the phone moves on by itself while it plays (the
 * PC only sends jumps). Dragging or tapping seeks once, on release. Not shown
 * when the player does not give the length and position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeekBar(media: Media, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, style: SeekBarStyle = LocalSeekBarStyle.current) {
    val length = media.lengthMs?.takeIf { it > 0 } ?: return
    if (media.positionMs == null) return

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(media) {
        now = SystemClock.elapsedRealtime()
        while (media.playing) {
            // Wake as the time shown turns to its next second.
            val played = (media.positionMs ?: 0) + (now - media.receivedAt)
            delay(TICK_MS - played.mod(TICK_MS))
            now = SystemClock.elapsedRealtime()
        }
    }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Where the phone last seeked to, until the PC says where it is.
    var held by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(media) { held = null }
    LaunchedEffect(held) {
        if (held != null) {
            delay(SEEK_HOLD_MS)
            held = null
        }
    }

    val played = held?.let { (target, at) -> if (media.playing) target + (now - at) else target }?.coerceIn(0, length)
        ?: playedMs(media, now - media.receivedAt)
        ?: 0
    val shown = dragging?.let { (it * length).toLong() } ?: played
    val fill = style.color.takeOrElse { YaeColors.accent }
    val enabled = media.canSeek

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (style.showTimes) Time(formatTimer(shown))
        Slider(
            value = (shown.toFloat() / length).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let {
                    val target = (it * length).toLong()
                    onSeek(target)
                    held = target to SystemClock.elapsedRealtime()
                }
                dragging = null
            },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(thumbColor = YaeColors.text, activeTrackColor = fill, inactiveTrackColor = YaeColors.track),
            // The slider only places an empty thumb (for touch); the ball is
            // drawn with the line so it sits right on it.
            thumb = { Spacer(Modifier.size(style.thumbSize)) },
            track = { state ->
                val fraction = state.value.coerceIn(0f, 1f)
                val colors = YaeColors
                val done = if (enabled) fill else colors.muted
                val ball = enabled && style.thumbSize > 0.dp
                Canvas(Modifier.fillMaxWidth().height(max(style.trackHeight, style.thumbSize))) {
                    val line = style.trackHeight.toPx()
                    val top = (size.height - line) / 2
                    val radius = CornerRadius(line / 2)
                    val x = size.width * fraction
                    drawRoundRect(colors.track, Offset(0f, top), Size(size.width, line), radius)
                    drawRoundRect(done, Offset(0f, top), Size(x, line), radius)
                    if (ball) drawCircle(fill, style.thumbSize.toPx() / 2, Offset(x, size.height / 2))
                }
            },
        )
        if (style.showTimes) Time(if (style.showRemaining) "-" + formatTimer(length - shown) else formatTimer(length))
    }
}

@Composable
private fun Time(text: String) {
    Text(text, fontFamily = Mono, fontSize = 11.sp, color = YaeColors.muted, maxLines = 1)
}
