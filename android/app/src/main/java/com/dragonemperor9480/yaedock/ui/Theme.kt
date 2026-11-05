package com.dragonemperor9480.yaedock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R

/**
 * The dock's palette: near-black ground, dark cards, one mint accent for live
 * values, amber for heat and charging, red only for recording and shutting down.
 */
object YaeColors {
    val background = Color(0xFF090B0F)
    val card = Color(0xFF12161D)
    /** Buttons and chips inside a card. */
    val raised = Color(0xFF1A2029)
    /** Gauge tracks and dividers. */
    val track = Color(0xFF252C37)
    val text = Color(0xFFEAF0F6)
    /** Labels and secondary text; readable on every surface above. */
    val muted = Color(0xFF8D98A8)
    val accent = Color(0xFF5BE7C4)
    val onAccent = Color(0xFF06110E)
    /** A selected chip or the current tab. */
    val accentSoft = Color(0xFF1C3631)
    /** Cached memory, beside the accent. */
    val accentDim = Color(0xFF2E6F61)
    val amber = Color(0xFFFFB547)
    val amberSoft = Color(0xFF3A2C14)
    val amberText = Color(0xFFFFC46B)
    val red = Color(0xFFFF6B6B)
    val redSoft = Color(0xFF3A1A1D)
    val redText = Color(0xFFFF8A8A)
}

/** Space Grotesk for text; one variable font file covers every weight. */
private fun spaceGrotesk(weight: FontWeight) = Font(
    R.font.space_grotesk,
    weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

private fun jetBrainsMono(weight: FontWeight) = Font(
    R.font.jetbrains_mono,
    weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Grotesk = FontFamily(
    spaceGrotesk(FontWeight.Light),
    spaceGrotesk(FontWeight.Normal),
    spaceGrotesk(FontWeight.Medium),
    spaceGrotesk(FontWeight.SemiBold),
    spaceGrotesk(FontWeight.Bold),
)

/** For numbers: every digit is as wide as the next, so values do not jump as they change. */
val Mono = FontFamily(
    jetBrainsMono(FontWeight.Normal),
    jetBrainsMono(FontWeight.Medium),
    jetBrainsMono(FontWeight.SemiBold),
)

private val typography = Typography().let { base ->
    fun TextStyle.grotesk() = copy(fontFamily = Grotesk)
    Typography(
        displayLarge = base.displayLarge.grotesk(),
        displayMedium = base.displayMedium.grotesk(),
        displaySmall = base.displaySmall.grotesk(),
        headlineLarge = base.headlineLarge.grotesk().copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.grotesk().copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.grotesk().copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.grotesk().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.grotesk().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.grotesk(),
        bodyLarge = base.bodyLarge.grotesk(),
        bodyMedium = base.bodyMedium.grotesk(),
        bodySmall = base.bodySmall.grotesk(),
        labelLarge = base.labelLarge.grotesk(),
        labelMedium = base.labelMedium.grotesk(),
        labelSmall = base.labelSmall.grotesk(),
    )
}

/** Small capitals over each card, such as "CPU" or "SOUND". */
val CardLabel = TextStyle(
    fontFamily = Grotesk,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    letterSpacing = 1.1.sp,
    color = YaeColors.muted,
)

@Composable
fun YaeDockTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = YaeColors.accent,
        onPrimary = YaeColors.onAccent,
        primaryContainer = YaeColors.accentSoft,
        onPrimaryContainer = YaeColors.accent,
        secondary = YaeColors.amber,
        secondaryContainer = YaeColors.raised,
        onSecondaryContainer = YaeColors.text,
        background = YaeColors.background,
        onBackground = YaeColors.text,
        surface = YaeColors.background,
        onSurface = YaeColors.text,
        surfaceVariant = YaeColors.track,
        onSurfaceVariant = YaeColors.muted,
        surfaceContainerLowest = YaeColors.background,
        surfaceContainerLow = YaeColors.card,
        surfaceContainer = YaeColors.card,
        surfaceContainerHigh = YaeColors.card,
        surfaceContainerHighest = YaeColors.raised,
        outline = YaeColors.muted,
        outlineVariant = YaeColors.track,
        error = YaeColors.red,
        onError = YaeColors.onAccent,
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
