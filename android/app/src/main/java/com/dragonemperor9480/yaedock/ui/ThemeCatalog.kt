package com.dragonemperor9480.yaedock.ui

import android.content.res.AssetManager
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A theme the user can pick in Settings. */
data class YaeTheme(val id: String, val name: String, val description: String, val palette: YaePalette)

/**
 * The themes in assets/themes, one JSON file each. Adding a theme is adding a
 * file: an id, a name, a short description, its place in the list, whether it
 * is dark, and every colour of [YaePalette] as "#RRGGBB" or "#AARRGGBB".
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
        require(file.id.isNotBlank()) { "a theme needs an id" }
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
        require(hex.length in setOf(7, 9) && hex[0] == '#') { "\"$hex\" is not a colour like #12161D" }
        val value = hex.substring(1).toLong(16)
        return Color(if (hex.length == 7) value or 0xFF000000 else value)
    }

    private val BUILT_IN = YaeTheme(DEFAULT_ID, "Midnight", "Near-black with mint, the original look.", Midnight)

    @Serializable
    private class ThemeFile(
        val id: String,
        val name: String,
        val description: String = "",
        val order: Int = Int.MAX_VALUE,
        val dark: Boolean,
        val colors: ThemeColors,
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
