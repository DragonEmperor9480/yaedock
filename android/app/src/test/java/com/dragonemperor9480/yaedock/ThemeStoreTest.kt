package com.dragonemperor9480.yaedock

import com.dragonemperor9480.yaedock.ui.ThemeCatalog
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStoreTest {
    private val builtIn = File("src/main/assets/themes").listFiles { f -> f.extension == "json" }.orEmpty()
        .map { ThemeCatalog.parse(it.readText()) }
        .let(ThemeCatalog::order)
    private val example = File("../../themes/example.json").readText()
    private val dir = Files.createTempDirectory("themes").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun store() = ThemeStore(builtIn, File(dir, "themes"))

    @Test
    fun importsTheExample() {
        val store = store()
        val check = store.check(example)
        check as ThemeCheck.Valid
        assertEquals(emptyList<String>(), check.warnings)
        assertFalse(check.replaces)

        store.save(check)
        val imported = store.themes.value.last()
        assertEquals("my-theme", imported.id)
        assertTrue(imported.custom)
        // A fresh start finds it again.
        assertEquals(store.themes.value, store().themes.value)
    }

    @Test
    fun replacesAnImportWithTheSameId() {
        val store = store()
        store.save(store.check(example) as ThemeCheck.Valid)
        val renamed = store.check(example.replace("\"name\": \"My theme\"", "\"name\": \"Mine\""))
        renamed as ThemeCheck.Valid
        assertTrue(renamed.replaces)
        store.save(renamed)
        assertEquals(listOf("Mine"), store.themes.value.filter { it.custom }.map { it.name })
    }

    @Test
    fun refusesABuiltInId() {
        val check = store().check(example.replace("\"id\": \"my-theme\"", "\"id\": \"paper\""))
        check as ThemeCheck.Invalid
        assertTrue(check.reason, check.reason.contains("built-in"))
    }

    @Test
    fun refusesABrokenFile() {
        assertTrue(store().check("{ not json") is ThemeCheck.Invalid)
        val badColour = store().check(example.replace("\"#5BE7C4\"", "\"mint\""))
        badColour as ThemeCheck.Invalid
        assertTrue(badColour.reason, badColour.reason.contains("\"mint\" is not a colour"))
        assertTrue(store().check(" ".repeat(ThemeStore.MAX_SIZE + 1)) is ThemeCheck.Invalid)
    }

    @Test
    fun warnsAboutHardToReadColoursButAllowsThem() {
        val check = store().check(example.replace("\"muted\": \"#8D98A8\"", "\"muted\": \"#2A2F36\""))
        check as ThemeCheck.Valid
        assertTrue(check.warnings.toString(), check.warnings.any { it.startsWith("muted text") })
    }

    @Test
    fun deletesOnlyImportedThemes() {
        val store = store()
        store.save(store.check(example) as ThemeCheck.Valid)
        store.delete("midnight")
        store.delete("my-theme")
        assertEquals(builtIn, store.themes.value)
    }
}
