package com.dragonemperor9480.yaedock.ui

import android.content.Context
import android.graphics.Picture
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGExternalFileResolver
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.clock.ClockColors
import com.dragonemperor9480.yaedock.clock.ClockDesign
import com.dragonemperor9480.yaedock.clock.ClockSvg
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.delay

/** Where a clock sits in its space when the shapes differ: `xMinYMid` keeps it to the left. */
enum class ClockAlign(internal val ratio: PreserveAspectRatio) {
    START(PreserveAspectRatio.of("xMinYMid meet")),
    CENTER(PreserveAspectRatio.LETTERBOX),
    TOP_START(PreserveAspectRatio.of("xMinYMin meet")),
}

/**
 * [design] drawn at the time now, redrawn as each minute starts (each second
 * when it shows seconds), or at [at] for a still preview. Text placeholders
 * are filled in and the file is read again for each new time; reading a clock
 * takes about a millisecond.
 */
@Composable
fun ClockFace(
    design: ClockDesign,
    colors: ClockColors,
    userName: String,
    modifier: Modifier = Modifier,
    align: ClockAlign = ClockAlign.CENTER,
    at: LocalDateTime? = null,
) {
    val template = design.svg ?: return
    val now = at ?: rememberTicker(design.ticksEverySecond)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val twentyFourHour = DateFormat.is24HourFormat(context)
    // Only for a new time or look: the screens around a clock redraw every second for their stats.
    val filled = remember(template, now, twentyFourHour, locale, userName, colors, design.ticksEverySecond) {
        ClockSvg.fill(
            template,
            ClockSvg.values(now, twentyFourHour, locale, userName, greeting(now.hour, userName), colors, design.ticksEverySecond),
        )
    }
    val svg = remember(filled) {
        runCatching { SVG.getFromString(filled) }
            .onFailure { Log.w(TAG, "Cannot read clock ${design.id}", it) }
            .getOrNull()
    }
    Canvas(modifier) {
        svg ?: return@Canvas
        drawIntoCanvas { canvas ->
            svg.renderToCanvas(
                canvas.nativeCanvas,
                RenderOptions().viewPort(0f, 0f, size.width, size.height).preserveAspectRatio(align.ratio),
            )
        }
    }
}

/** The time now, updated just after each minute (or second) starts. */
@Composable
private fun rememberTicker(everySecond: Boolean): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(everySecond) {
        val step = if (everySecond) 1_000L else 60_000L
        while (true) {
            now = LocalDateTime.now()
            // From the clock each time, so a late wake never adds up.
            delay(step - System.currentTimeMillis() % step + 5)
        }
    }
    return now
}

/** The colours of [palette] by name, for a clock on a card or a screen of that theme. */
fun clockColors(palette: YaePalette): ClockColors = mapOf(
    "background" to palette.background, "card" to palette.card, "raised" to palette.raised,
    "track" to palette.track, "text" to palette.text, "muted" to palette.muted,
    "accent" to palette.accent, "onAccent" to palette.onAccent, "accentSoft" to palette.accentSoft,
    "accentDim" to palette.accentDim, "amber" to palette.amber, "amberSoft" to palette.amberSoft,
    "amberText" to palette.amberText, "red" to palette.red, "redSoft" to palette.redSoft, "redText" to palette.redText,
).mapValues { svgColor(it.value) }

/**
 * Dimmer colours for the night screen, on true black whatever the theme: the
 * accent and amber are the night screen's own, the rest are fixed greys.
 */
fun nightClockColors(palette: YaePalette): ClockColors {
    val black = Color.Black
    val accent = palette.nightAccent
    val amber = palette.nightAmber
    val red = Color(0xFFB85C5C)
    return mapOf(
        "background" to black, "card" to black, "raised" to Color(0xFF0E1116),
        "track" to Color(0xFF1A1F27), "text" to Color(0xFFD5DDE8), "muted" to Color(0xFF7D8796),
        "accent" to accent, "onAccent" to black, "accentSoft" to lerp(black, accent, 0.22f),
        "accentDim" to lerp(black, accent, 0.5f), "amber" to amber, "amberSoft" to lerp(black, amber, 0.22f),
        "amberText" to amber, "red" to red, "redSoft" to lerp(black, red, 0.22f), "redText" to red,
    ).mapValues { svgColor(it.value) }
}

private fun svgColor(color: Color): String {
    val argb = color.toArgb()
    val rgb = argb and 0xFFFFFF
    val alpha = color.alpha
    return if (alpha >= 1f) {
        "#%06X".format(Locale.ROOT, rgb)
    } else {
        "rgba(%d,%d,%d,%.3f)".format(Locale.ROOT, rgb shr 16, rgb shr 8 and 0xFF, rgb and 0xFF, alpha)
    }
}

/** Why [design] can't be drawn, or null when it draws. Tried at a sample time with sample colours. */
fun clockDrawError(design: ClockDesign): String? {
    val template = design.svg ?: return null
    val values = ClockSvg.values(
        LocalDateTime.of(2026, 11, 6, 21, 47, 23), true, Locale.ROOT, "Name", "Good evening, Name",
        nightClockColors(Midnight), true,
    )
    return runCatching {
        val svg = SVG.getFromString(ClockSvg.fill(template, values))
        Picture().also { svg.renderToCanvas(it.beginRecording(400, 200), RenderOptions().viewPort(0f, 0f, 400f, 200f)) }.endRecording()
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }
}

/**
 * Lets clocks use the app's own fonts, by their names, at any weight: Space
 * Grotesk and JetBrains Mono are variable fonts. Other names fall back to the
 * phone's fonts. Installed once, by the app.
 */
class ClockFonts(context: Context) : SVGExternalFileResolver() {
    private val app = context.applicationContext
    private val cache = HashMap<String, Typeface?>()

    override fun resolveFont(fontFamily: String?, fontWeight: Int, fontStyle: String?): Typeface? {
        val family = fontFamily?.trim('\'', '"', ' ')?.lowercase() ?: return null
        val (font, range) = when (family) {
            "space grotesk" -> R.font.space_grotesk to 300..700
            "jetbrains mono" -> R.font.jetbrains_mono to 100..800
            else -> return null
        }
        val weight = fontWeight.coerceIn(range)
        return synchronized(cache) {
            cache.getOrPut("$family/$weight") {
                runCatching {
                    Typeface.Builder(fontFile(font, family)).setFontVariationSettings("'wght' $weight").setWeight(weight).build()
                }.onFailure { Log.w(TAG, "Cannot load $family", it) }.getOrNull()
            }
        }
    }

    // Clocks only load their own pictures, as data: URLs; nothing from files or the web.
    override fun resolveImage(filename: String?) = null

    override fun resolveCSSStyleSheet(url: String?) = null

    /** A variable font can only be given its weight from a file, so it is copied out once. */
    private fun fontFile(font: Int, family: String): File {
        val file = File(app.cacheDir, "clock-fonts/${family.replace(' ', '-')}.ttf")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            // Written beside it first, so a copy cut short is never used.
            val part = File(file.path + ".part")
            app.resources.openRawResource(font).use { input -> part.outputStream().use(input::copyTo) }
            part.renameTo(file)
        }
        return file
    }

    companion object {
        /** Sets up clock drawing for the whole app; called once at start. */
        fun install(context: Context) {
            // Entities could expand a small file into a huge one.
            SVG.setInternalEntitiesEnabled(false)
            SVG.registerExternalFileResolver(ClockFonts(context))
        }
    }
}

private const val TAG = "ClockFace"

/** The clocks chosen in Settings, and the name the greeting uses. */
data class ClockChoice(val dock: ClockDesign, val night: ClockDesign, val userName: String)

val LocalClocks = androidx.compose.runtime.staticCompositionLocalOf {
    ClockChoice(ClockDesign.CLASSIC, ClockDesign.CLASSIC, "")
}
