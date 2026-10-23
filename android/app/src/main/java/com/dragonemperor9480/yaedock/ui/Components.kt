package com.dragonemperor9480.yaedock.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

val CardShape = RoundedCornerShape(18.dp)
val ButtonShape = RoundedCornerShape(14.dp)
val ChipShape = RoundedCornerShape(12.dp)

/** A dark rounded card, the building block of every screen. */
@Composable
fun DockCard(
    modifier: Modifier = Modifier,
    padding: Dp = 12.dp,
    spacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.clip(CardShape).background(YaeColors.card).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A card's small-capitals label, with an optional value on the right (such as a temperature). */
@Composable
fun CardHeader(label: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.weight(1f), style = CardLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke(this)
    }
}

/** A short number or note in monospace at the right of a card header. */
@Composable
fun HeaderValue(text: String, color: Color = YaeColors.muted) {
    Text(text, fontFamily = Mono, fontSize = 12.sp, color = color, maxLines = 1)
}

/** Text in the muted colour, one line. */
@Composable
fun Muted(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 11.sp,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    Text(
        text,
        modifier,
        fontSize = size,
        textAlign = textAlign,
        lineHeight = (size.value * 1.45f).sp,
        color = YaeColors.muted,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A number in monospace with a smaller muted unit after it: `9.4 / 15.5 GiB`, `34%`. */
@Composable
fun MonoValue(
    value: String,
    unit: String = "",
    size: TextUnit = 26.sp,
    color: Color = YaeColors.text,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            value,
            fontFamily = Mono,
            fontWeight = FontWeight.SemiBold,
            fontSize = size,
            letterSpacing = (size.value * -0.02f).sp,
            color = color,
            maxLines = 1,
        )
        if (unit.isNotEmpty()) {
            Text(
                unit,
                Modifier.padding(bottom = (size.value * 0.12f).dp),
                fontFamily = Mono,
                fontSize = (size.value * 0.46f).coerceAtLeast(10f).sp,
                color = YaeColors.muted,
                maxLines = 1,
            )
        }
    }
}

/** A square 44 dp button with an icon, the header's buttons. */
@Composable
fun SquareButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = YaeColors.card,
    tint: Color = YaeColors.text,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(ButtonShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(20.dp), tint = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

/**
 * A flat button: a raised chip inside a card, or the selected one in accent.
 * Used for outputs, power profiles, shortcuts and the like.
 */
@Composable
fun ChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: Painter? = null,
    iconTint: Color = Color.Unspecified,
    colors: Pair<Color, Color> = if (selected) YaeColors.accentSoft to YaeColors.accent else YaeColors.raised to YaeColors.text,
    height: Dp = 44.dp,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .height(height)
            .clip(ChipShape)
            .background(colors.first)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, Modifier.size(16.dp), tint = if (iconTint == Color.Unspecified) colors.second else iconTint)
        }
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = colors.second.copy(alpha = if (enabled) 1f else 0.4f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** A ring filled clockwise from the top with [percent], the value in monospace in its centre. */
@Composable
fun ValueRing(
    percent: Float,
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = YaeColors.accent,
    stroke: Dp = if (size >= 80.dp) 8.dp else 6.dp,
) {
    // No animation: values arrive every second, so an animated ring would keep
    // the phone redrawing almost all the time.
    val filled = percent.coerceIn(0f, 100f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val width = stroke.toPx()
            val inset = width / 2
            val arc = Size(this.size.width - width, this.size.height - width)
            drawArc(YaeColors.track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width))
            if (filled > 0f) {
                drawArc(
                    color, -90f, 360f * filled / 100f, false, Offset(inset, inset), arc,
                    style = Stroke(width, cap = StrokeCap.Round),
                )
            }
        }
        MonoValue("${percent.roundToInt()}", "%", size = (size.value * 0.26f).sp)
    }
}

/** A thin horizontal bar filled to [fraction], with an optional lighter second part after it. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier, color: Color = YaeColors.accent, extra: Float = 0f, height: Dp = 8.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(YaeColors.track, cornerRadius = radius)
        val used = size.width * fraction.coerceIn(0f, 1f)
        val more = size.width * extra.coerceIn(0f, 1f - fraction.coerceIn(0f, 1f))
        if (more > 0f) drawRoundRect(YaeColors.accentDim, size = Size(used + more, size.height), cornerRadius = radius)
        if (used > 0f) drawRoundRect(color, size = Size(used, size.height), cornerRadius = radius)
    }
}

/** Ten small blocks, like a battery's charge bars. */
@Composable
fun SegmentBar(percent: Float, modifier: Modifier = Modifier, color: Color = YaeColors.accent) {
    val lit = (percent / 10f).roundToInt().coerceIn(0, 10)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(10) { i ->
            Box(
                Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i < lit) color else YaeColors.track),
            )
        }
    }
}

/** A small filled dot, such as the connection status. */
@Composable
fun Dot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** An on/off switch drawn in the dock's colours. */
@Composable
fun DockSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, contentDescription: String) {
    Box(
        Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(CircleShape)
            .background(if (checked) YaeColors.accent else YaeColors.track)
            .clickable(role = Role.Switch, onClickLabel = contentDescription) { onCheckedChange(!checked) }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (checked) YaeColors.onAccent else YaeColors.muted))
    }
}

/**
 * A slider that follows the PC's value, except while the user is dragging it.
 *
 * While dragging, changes are sent at most every [SEND_INTERVAL_MS] so the PC
 * follows along without being flooded, and the final value is always sent.
 * After release the slider keeps showing that value until the PC reports it
 * (or [HOLD_MS] passes), so older updates still in flight cannot make it jump back.
 *
 * The row is [leading], the slider, the value, then [trailing].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncedSlider(
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    dimmed: Boolean = false,
) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    var held by remember { mutableStateOf<Int?>(null) }
    var lastSent by remember { mutableStateOf<Int?>(null) }
    var lastSentAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(value) { if (value == held) held = null }
    LaunchedEffect(held) {
        if (held != null) {
            delay(HOLD_MS)
            held = null
        }
    }

    val shown = dragging ?: held ?: value
    val fill = if (dimmed) YaeColors.muted else YaeColors.accent
    Row(modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading()
        Slider(
            value = shown.toFloat().coerceAtMost(max.toFloat()),
            onValueChange = {
                val percent = it.roundToInt()
                dragging = percent
                val now = SystemClock.uptimeMillis()
                if (percent != lastSent && now - lastSentAt >= SEND_INTERVAL_MS) {
                    onChange(percent)
                    lastSent = percent
                    lastSentAt = now
                }
            },
            onValueChangeFinished = {
                dragging?.let { percent ->
                    if (percent != lastSent) onChange(percent)
                    held = percent
                }
                dragging = null
                lastSent = null
            },
            valueRange = 0f..max.toFloat(),
            modifier = Modifier.weight(1f).height(44.dp),
            colors = SliderDefaults.colors(thumbColor = YaeColors.text, activeTrackColor = fill, inactiveTrackColor = YaeColors.track),
            thumb = {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(YaeColors.text)
                        .border(3.dp, fill, CircleShape),
                )
            },
            track = { state ->
                val range = state.valueRange
                val fraction = ((state.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val radius = CornerRadius(size.height / 2)
                    drawRoundRect(YaeColors.track, cornerRadius = radius)
                    drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = radius)
                }
            },
        )
        Text(
            "$shown",
            Modifier.width(30.dp),
            fontFamily = Mono,
            fontSize = 12.sp,
            // Past 100% the PC amplifies the sound, which is worth noticing.
            color = if (shown > 100) YaeColors.amber else YaeColors.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        trailing()
    }
}

private const val SEND_INTERVAL_MS = 80L
private const val HOLD_MS = 1500L

/** A gap that grows to push what follows to the end of a row or column. */
@Composable
fun RowScope.Fill() = Spacer(Modifier.weight(1f))

@Composable
fun ColumnScope.Fill() = Spacer(Modifier.weight(1f))
