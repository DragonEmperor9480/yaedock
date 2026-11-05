package com.dragonemperor9480.yaedock.ui

import android.content.res.AssetManager
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A theme the user can pick in Settings; [custom] ones were imported and can be deleted. */
data class YaeTheme(
    val id: String,
    val name: String,
    val description: String,
    val palette: YaePalette,
    val custom: Boolean = false,
)

/**
 * The themes in assets/themes, one JSON file each. Adding a theme is adding a
 * file: an id, a name, a short description, its place in the list, whether it
 * is dark, and every colour of [YaePalette] as "#RRGGBB" or "#AARRGGBB".
 * themes/example.json at the top of the repository says where each colour goes.
 */
object ThemeCatalog {
    const val DEFAULT_ID = "midnight"
    private const val DIR = "themes"
    private const val TAG = "ThemeCatalog"

    /** A file with a missing or unknown key is refused, so a typo cannot slip through. */
    private val json = Json { ignoreUnknownKeys = false }

    /** Every theme that loads, in order; Midnight from code if none does. */
    fun load(assets: AssetManager): List<YaeTheme> {
        val themes = assets.list(DIR).orEmpty().filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching { assets.open("$DIR/$name").bufferedReader().use { parse(it.readText()) } }
                .onFailure { Log.w(TAG, "Skipping theme $name", it) }
                .getOrNull()
        }
        return order(themes).ifEmpty { listOf(BUILT_IN) }
    }

    /** Sorts by each file's place, and keeps the first theme of each id. */
    fun order(themes: List<Pair<Int, YaeTheme>>): List<YaeTheme> =
        themes.sortedBy { it.first }.map { it.second }.distinctBy { it.id }

    /** One theme file, with its place in the list. */
    fun parse(text: String): Pair<Int, YaeTheme> {
        val file = json.decodeFromString(ThemeFile.serializer(), text)
        require(ID.matches(file.id)) { "The id \"${file.id}\" needs to be 1 to 32 lowercase letters, digits or dashes" }
        require(file.name.isNotBlank() && file.name.length <= 32) { "The name needs to be 1 to 32 characters" }
        val c = file.colors
        val palette = YaePalette(
            dark = file.dark,
            background = color(c.background),
            card = color(c.card),
            raised = color(c.raised),
            track = color(c.track),
            text = color(c.text),
            muted = color(c.muted),
            accent = color(c.accent),
            onAccent = color(c.onAccent),
            accentSoft = color(c.accentSoft),
            accentDim = color(c.accentDim),
            amber = color(c.amber),
            amberSoft = color(c.amberSoft),
            amberText = color(c.amberText),
            red = color(c.red),
            redSoft = color(c.redSoft),
            redText = color(c.redText),
        )
        return file.order to YaeTheme(file.id, file.name, file.description, palette)
    }

    /** "#RRGGBB" or "#AARRGGBB". */
    fun color(hex: String): Color {
        require(HEX.matches(hex)) { "\"$hex\" is not a colour like #12161D" }
        val value = hex.substring(1).toLong(16)
        return Color(if (hex.length == 7) value or 0xFF000000 else value)
    }

    /**
     * Where [p] would be hard to read, in words; empty when it reads well.
     * Text needs 7:1 against the surfaces it sits on, small coloured text 4.5:1,
     * and gauges, sliders and the night clock 3:1.
     */
    fun problems(p: YaePalette): List<String> = buildList {
        fun need(what: String, fg: Color, bg: Color, min: Double) {
            val ratio = contrast(fg, bg)
            if (ratio < min) add("%s: %.1f:1, needs %.1f:1".format(what, ratio, min))
        }
        for ((name, surface) in listOf("the background" to p.background, "cards" to p.card, "buttons" to p.raised)) {
            need("text on $name", p.text, surface, 7.0)
            need("muted text on $name", p.muted, surface, 4.5)
            need("accent on $name", p.accent, surface, 3.0)
        }
        need("onAccent on accent", p.onAccent, p.accent, 4.5)
        need("amber on cards", p.amber, p.card, 3.0)
        need("accent on accentSoft", p.accent, p.accentSoft, 3.0)
        need("amberText on amberSoft", p.amberText, p.amberSoft, 4.5)
        need("redText on redSoft", p.redText, p.redSoft, 4.5)
        need("amberText on cards", p.amberText, p.card, 4.5)
        need("redText on cards", p.redText, p.card, 4.5)
        need("track on cards", p.track, p.card, 1.15)
        // The night dock is always black, and dim on purpose.
        need("accent on the black night dock", p.nightAccent, Color.Black, 3.0)
        if (p.dark != (p.background.luminance() < 0.5f)) {
            add(if (p.dark) "\"dark\" is true, but the background is light" else "\"dark\" is false, but the background is dark")
        }
    }

    /** A parse failure as one plain sentence, without the library's hints. */
    fun describe(error: Throwable): String {
        val line = error.message.orEmpty().lineSequence().firstOrNull().orEmpty()
        return line
            .replace(Regex(" for type with serial name '[^']*'"), "")
            .replace(Regex(" at offset \\d+"), "")
            .replace(Regex("(,)? but it was missing"), " but is missing")
            .ifBlank { "This is not a theme file" }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    private val ID = Regex("[a-z0-9-]{1,32}")
    private val HEX = Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")

    private val BUILT_IN = YaeTheme(DEFAULT_ID, "Midnight", "Near-black with mint, the original look.", Midnight)

    @Serializable
    private class ThemeFile(
        val id: String,
        val name: String,
        val description: String = "",
        val order: Int = Int.MAX_VALUE,
        val dark: Boolean,
        val colors: ThemeColors,
        /** Notes for whoever edits the file, as in example.json; the app ignores them. */
        @Suppress("unused") val help: Map<String, String> = emptyMap(),
    )

    @Serializable
    private class ThemeColors(
        val background: String,
        val card: String,
        val raised: String,
        val track: String,
        val text: String,
        val muted: String,
        val accent: String,
        val onAccent: String,
        val accentSoft: String,
        val accentDim: String,
        val amber: String,
        val amberSoft: String,
        val amberText: String,
        val red: String,
        val redSoft: String,
        val redText: String,
    )
}
