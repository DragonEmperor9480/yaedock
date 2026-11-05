package com.dragonemperor9480.yaedock.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The fox's colours, from the app icon. */
internal object FoxColors {
    val pink = Color(0xFFF49AC1)
    val deep = Color(0xFFC2457E)
    val light = Color(0xFFFFF1F6)
    val ink = Color(0xFF2A0F1E)
    /** Behind the record dot. */
    val pinkSoft = Color(0xFF3A1A2A)
}

/** Paths from the app icon, on its 108-unit grid. */
private object Fox {
    val face = path("M35,29L46,44H62L73,29L77,52L70,64L54,78L38,64L31,52Z")
    val ears = listOf(path("M36.5,33.5L43.5,43.5L34,47Z"), path("M71.5,33.5L64.5,43.5L74,47Z"))
    val muzzle = path("M38,63L46.5,58.5L54,64L61.5,58.5L70,63L54,78Z")
    val eyes = path("M41,51L48.5,55M67,51L59.5,55")
    val nose = path("M51,64.5H57L54,68Z")

    private fun path(data: String): Path = PathParser().parsePathString(data).toPath()
}

/** A path filled and outlined in one colour, which rounds its corners like the icon. */
private fun DrawScope.solid(path: Path, color: Color, outline: Float) {
    drawPath(path, color)
    drawPath(path, color, style = Stroke(outline, join = StrokeJoin.Round))
}

/** The fox in icon units; [blink] squashes the eyes (1 is open). */
internal fun DrawScope.drawFox(blink: Float = 1f) {
    solid(Fox.face, FoxColors.pink, 2f)
    for (ear in Fox.ears) solid(ear, FoxColors.deep, 1.2f)
    solid(Fox.muzzle, FoxColors.light, 2f)
    scale(1f, blink, pivot = Offset(54f, 53f)) {
        drawPath(Fox.eyes, FoxColors.ink, style = Stroke(3.4f, cap = StrokeCap.Round))
    }
    solid(Fox.nose, FoxColors.ink, 1.4f)
}

/**
 * The app icon, alive: the gauge sweeps up and settles, the fox bobs, the
 * mint dot pulses and an amber dot circles it all.
 */
@Composable
fun FoxGauge(modifier: Modifier) {
    // In the icon's units: the gauge is 141.4 long and 96 of it is filled.
    val filled = remember { Animatable(38f) }
    LaunchedEffect(Unit) {
        filled.animateTo(96f, keyframes {
            durationMillis = 2400
            38f at 0
            118f at 1300 using FastOutSlowInEasing
            96f at 2400 using FastOutSlowInEasing
        })
    }
    val motion = rememberInfiniteTransition(label = "fox")
    val bob by motion.animateFloat(0f, -1.6f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "bob")
    val pulse by motion.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "pulse")
    val orbit by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "orbit")
    Canvas(modifier.sizeIn(maxWidth = 260.dp, maxHeight = 260.dp).aspectRatio(1f).semantics {
        contentDescription = "The YaeDock fox inside a gauge"
    }) {
        scale(size.minDimension / 108f, pivot = Offset.Zero) {
            drawCircle(
                YaeColors.card, 44f, Offset(54f, 54f),
                style = Stroke(0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.2f, 3.4f))),
            )
            val arc = Size(60f, 60f)
            val corner = Offset(24f, 24f)
            drawArc(Color(0xFF1E2530), 135f, 270f, false, corner, arc, style = Stroke(4.4f, cap = StrokeCap.Round))
            drawArc(FoxColors.pink, 135f, 270f * filled.value / 141.4f, false, corner, arc, style = Stroke(4.4f, cap = StrokeCap.Round))
            translate(0f, 1f + bob) {
                scale(0.6f, pivot = Offset(54f, 54f)) { drawFox() }
            }
            drawCircle(YaeColors.accent.copy(alpha = pulse), 2.6f, Offset(54f, 82f))
            rotate(orbit, pivot = Offset(54f, 54f)) { drawCircle(YaeColors.amber, 1.6f, Offset(54f, 10f)) }
        }
    }
}
