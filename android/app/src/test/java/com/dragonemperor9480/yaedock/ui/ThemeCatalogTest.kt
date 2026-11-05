package com.dragonemperor9480.yaedock.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeCatalogTest {
    /** Unit tests run in the app module, so the shipped files are read from the source tree. */
    private val files = File("src/main/assets/themes").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
    private val themes = ThemeCatalog.order(files.map { ThemeCatalog.parse(it.readText()) })

    @Test
    fun everyFileLoads() {
        assertTrue("no theme files found", files.isNotEmpty())
        assertEquals("two files share an id", files.size, themes.size)
    }

    @Test
    fun midnightComesFirstAndMatchesTheBuiltIn() {
        assertEquals(ThemeCatalog.DEFAULT_ID, themes.first().id)
        assertEquals(Midnight, themes.first().palette)
    }

    @Test
    fun everyThemeIsReadable() {
        val problems = themes.flatMap { theme ->
            val p = theme.palette
            buildList {
                fun need(what: String, fg: Color, bg: Color, min: Double) {
                    val ratio = contrast(fg, bg)
                    if (ratio < min) add("${theme.id}: $what is %.2f, needs %.1f".format(ratio, min))
                }
                for ((name, surface) in listOf("background" to p.background, "card" to p.card, "raised" to p.raised)) {
                    need("text on $name", p.text, surface, 7.0)
                    need("muted on $name", p.muted, surface, 4.5)
                    need("accent on $name", p.accent, surface, 3.0)
                }
                need("onAccent on accent", p.onAccent, p.accent, 4.5)
                // Amber colours warm temperatures, as text too.
                need("amber on card", p.amber, p.card, 3.0)
                need("accent on accentSoft", p.accent, p.accentSoft, 3.0)
                need("amberText on amberSoft", p.amberText, p.amberSoft, 4.5)
                need("redText on redSoft", p.redText, p.redSoft, 4.5)
                need("amberText on card", p.amberText, p.card, 4.5)
                need("redText on card", p.redText, p.card, 4.5)
                need("track on card", p.track, p.card, 1.15)
                // The night dock is always black, and dim on purpose: its accent
                // only draws the large clock digits and the rings.
                need("night accent on black", p.nightAccent, Color.Black, 3.0)
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun darkMatchesTheGround() {
        for (theme in themes) {
            assertEquals(theme.id, theme.palette.background.luminance() < 0.5f, theme.palette.dark)
        }
    }

    @Test
    fun readsColours() {
        assertEquals(Color(0xFF12161D), ThemeCatalog.color("#12161D"))
        assertEquals(Color(0x8012161D), ThemeCatalog.color("#8012161D"))
        assertThrows(IllegalArgumentException::class.java) { ThemeCatalog.color("12161D") }
        assertThrows(IllegalArgumentException::class.java) { ThemeCatalog.color("#1216") }
    }

    @Test
    fun refusesAMissingColour() {
        val text = files.first().readText().replace(Regex("\\s*\"amberSoft\": \"#[0-9A-Fa-f]+\","), "")
        assertFalse(text.contains("amberSoft"))
        assertThrows(Exception::class.java) { ThemeCatalog.parse(text) }
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }
}
