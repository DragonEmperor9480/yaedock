package com.dragonemperor9480.yaedock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R

/**
 * A theme's colours. Every theme keeps the same roles: a ground, cards, one
 * accent for live values, amber for heat and charging, red only for recording
 * and shutting down. Themes live in assets/themes as JSON; see [ThemeCatalog].
 */
@Immutable
data class YaePalette(
    /** Whether the ground is dark, which picks light or dark system bar icons. */
    val dark: Boolean,
    val background: Color,
    val card: Color,
    /** Buttons and chips inside a card. */
    val raised: Color,
    /** Gauge tracks and dividers. */
    val track: Color,
    val text: Color,
    /** Labels and secondary text; readable on every surface above. */
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    /** A selected chip or the current tab. */
    val accentSoft: Color,
    /** Cached memory, beside the accent. */
    val accentDim: Color,
    val amber: Color,
    val amberSoft: Color,
    val amberText: Color,
    val red: Color,
    val redSoft: Color,
    val redText: Color,
) {
    /** The floating tab bar, over a blurred screen. */
    val glassTint = if (dark) lerp(raised, text, 0.06f).copy(alpha = 0.55f) else card.copy(alpha = 0.78f)
    val glassEdge = text.copy(alpha = if (dark) 0.2f else 0.1f)
    /** Older phones cannot blur, so the bar is nearly solid there. */
    val glassFallback = (if (dark) raised else card).copy(alpha = 0.95f)
    /** Tabs that are not chosen. */
    val navIdle = lerp(text, muted, 0.35f)
    val navSelected = accent.copy(alpha = 0.2f)
    /** The small handle that brings the tab bar back. */
    val handle = lerp(track, muted, 0.45f)

    /** The night dock is always on black, so it takes a dimmed accent that reads there. */
    val nightAccent = if (dark) lerp(Color.Black, accent, 0.72f) else lerp(accent, Color.White, 0.2f)
    val nightAmber = if (dark) lerp(Color.Black, amber, 0.79f) else amber
}

/** The first theme, built in so the app has colours even if no theme file loads. */
val Midnight = YaePalette(
    dark = true,
    background = Color(0xFF090B0F),
    card = Color(0xFF12161D),
    raised = Color(0xFF1A2029),
    track = Color(0xFF252C37),
    text = Color(0xFFEAF0F6),
    muted = Color(0xFF8D98A8),
    accent = Color(0xFF5BE7C4),
    onAccent = Color(0xFF06110E),
    accentSoft = Color(0xFF1C3631),
    accentDim = Color(0xFF2E6F61),
    amber = Color(0xFFFFB547),
    amberSoft = Color(0xFF3A2C14),
    amberText = Color(0xFFFFC46B),
    red = Color(0xFFFF6B6B),
    redSoft = Color(0xFF3A1A1D),
    redText = Color(0xFFFF8A8A),
)

val LocalYaePalette = staticCompositionLocalOf { Midnight }

/** The current theme's colours. */
val YaeColors: YaePalette
    @Composable @ReadOnlyComposable
    get() = LocalYaePalette.current

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
val CardLabel: TextStyle
    @Composable @ReadOnlyComposable
    get() = TextStyle(
        fontFamily = Grotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.1.sp,
        color = YaeColors.muted,
    )

@Composable
fun YaeDockTheme(palette: YaePalette = Midnight, content: @Composable () -> Unit) {
    val p = palette
    val colors = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.accent,
        secondary = p.amber,
        secondaryContainer = p.raised,
        onSecondaryContainer = p.text,
        background = p.background,
        onBackground = p.text,
        surface = p.background,
        onSurface = p.text,
        surfaceVariant = p.track,
        onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.card,
        surfaceContainer = p.card,
        surfaceContainerHigh = p.card,
        surfaceContainerHighest = p.raised,
        outline = p.muted,
        outlineVariant = p.track,
        error = p.red,
        onError = p.onAccent,
    )
    CompositionLocalProvider(LocalYaePalette provides palette) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
