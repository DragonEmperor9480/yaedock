package com.dragonemperor9480.yaedock

import android.util.Log
import com.dragonemperor9480.yaedock.ui.ThemeCatalog
import com.dragonemperor9480.yaedock.ui.YaeTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What checking a theme file found, before anything is saved. */
sealed interface ThemeCheck {
    /** The file cannot be a theme; [reason] says why in one sentence. */
    data class Invalid(val reason: String) : ThemeCheck

    /**
     * A theme that loads. [warnings] say where it would be hard to read, and
     * [replaces] whether an imported theme with the same id is already here.
     */
    data class Valid(val theme: YaeTheme, val text: String, val warnings: List<String>, val replaces: Boolean) : ThemeCheck
}

/**
 * The built-in themes from the APK, then the ones the user imported, which are
 * kept as their own JSON files in [dir] and listed by name.
 */
class ThemeStore(private val builtIn: List<YaeTheme>, private val dir: File) {
    private val _themes = MutableStateFlow(builtIn + loadImported())
    val themes: StateFlow<List<YaeTheme>> = _themes.asStateFlow()

    /** Reads [text] as a theme without saving it. */
    fun check(text: String): ThemeCheck {
        if (text.length > MAX_SIZE) return ThemeCheck.Invalid("This file is too big to be a theme")
        val theme = runCatching { ThemeCatalog.parse(text).second }
            .getOrElse { return ThemeCheck.Invalid(ThemeCatalog.describe(it)) }
        if (builtIn.any { it.id == theme.id }) {
            return ThemeCheck.Invalid("\"${theme.id}\" is the id of a built-in theme; give yours its own id")
        }
        val replaces = _themes.value.any { it.custom && it.id == theme.id }
        return ThemeCheck.Valid(theme.copy(custom = true), text, ThemeCatalog.problems(theme.palette), replaces)
    }

    /** Saves a theme that passed [check], replacing an imported one with the same id. */
    fun save(valid: ThemeCheck.Valid) {
        dir.mkdirs()
        File(dir, "${valid.theme.id}.json").writeText(valid.text)
        _themes.value = builtIn + loadImported()
    }

    fun delete(id: String) {
        if (builtIn.any { it.id == id }) return
        File(dir, "$id.json").delete()
        _themes.value = builtIn + loadImported()
    }

    private fun loadImported(): List<YaeTheme> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
            runCatching { ThemeCatalog.parse(file.readText()).second.copy(custom = true) }
                .onFailure { Log.w(TAG, "Skipping imported theme ${file.name}", it) }
                .getOrNull()
        }.filter { theme -> builtIn.none { it.id == theme.id } }.sortedBy { it.name.lowercase() }

    companion object {
        /** Far above any real theme, so a wrong file is refused before it is read in full. */
        const val MAX_SIZE = 64 * 1024
        private const val TAG = "ThemeStore"
    }
}
