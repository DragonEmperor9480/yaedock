package com.dragonemperor9480.yaedock.ui

import androidx.compose.ui.graphics.Color
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
        val problems = themes.flatMap { theme -> ThemeCatalog.problems(theme.palette).map { "${theme.id}: $it" } }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theExampleIsAThemeThatReadsWell() {
        val (_, example) = ThemeCatalog.parse(File("../../themes/example.json").readText())
        assertTrue(themes.none { it.id == example.id })
        assertEquals(emptyList<String>(), ThemeCatalog.problems(example.palette))
    }

    @Test
    fun flagsColoursThatAreHardToRead() {
        val grey = Midnight.copy(muted = Color(0xFF30363F), dark = false)
        val problems = ThemeCatalog.problems(grey)
        assertTrue(problems.toString(), problems.any { it.startsWith("muted text on cards") })
        assertTrue(problems.toString(), problems.any { it.contains("\"dark\" is false") })
    }

    @Test
    fun explainsWhyAFileIsRefused() {
        val missing = files.first().readText().replace(Regex("\\s*\"amberSoft\": \"#[0-9A-Fa-f]+\","), "")
        val why = ThemeCatalog.describe(runCatching { ThemeCatalog.parse(missing) }.exceptionOrNull()!!)
        assertTrue(why, why.contains("amberSoft"))
        assertFalse(why, why.contains("serial name"))

        val badId = files.first().readText().replace("\"id\": \"midnight\"", "\"id\": \"My Theme\"")
        val idWhy = ThemeCatalog.describe(runCatching { ThemeCatalog.parse(badId) }.exceptionOrNull()!!)
        assertTrue(idWhy, idWhy.contains("lowercase"))

        assertEquals("This is not a theme file", ThemeCatalog.describe(IllegalStateException("")))
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
        val text = files.first().readText().replace(Regex("\\s*\"redText\": \"#[0-9A-Fa-f]+\",?"), "")
        assertFalse(text.contains("redText"))
        assertThrows(Exception::class.java) { ThemeCatalog.parse(text) }
    }
}
