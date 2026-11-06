package com.dragonemperor9480.yaedock.clock

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ClockDesign.CLASSIC], the built-in clocks from the APK, then the ones the
 * user imported, kept as their own SVG files in [dir] and listed by name.
 * [drawError] tries to draw a clock and says why it can't, or returns null.
 */
class ClockStore(
    builtIn: List<ClockDesign>,
    private val dir: File,
    private val drawError: (ClockDesign) -> String?,
) {
    private val builtIn = listOf(ClockDesign.CLASSIC) + builtIn
    private val builtInIds = this.builtIn.map { it.id }.toSet()

    private val _clocks = MutableStateFlow(this.builtIn + loadImported())
    val clocks: StateFlow<List<ClockDesign>> = _clocks.asStateFlow()

    /** The clock with [id], or [ClockDesign.CLASSIC] when there is none (it was deleted). */
    fun find(id: String): ClockDesign = _clocks.value.find { it.id == id } ?: ClockDesign.CLASSIC

    /** Reads [text] as a clock without saving it, and tries drawing it. */
    fun check(text: String): ClockCheck {
        val imported = _clocks.value.filter { it.custom }.map { it.id }.toSet()
        val check = ClockSvg.check(text, builtInIds, imported)
        if (check !is ClockCheck.Valid) return check
        drawError(check.design)?.let { return ClockCheck.Invalid("This SVG can't be drawn: $it") }
        return check
    }

    /** Saves a clock that passed [check], replacing an imported one with the same id. */
    fun save(valid: ClockCheck.Valid) {
        dir.mkdirs()
        File(dir, "${valid.design.id}.svg").writeText(valid.file)
        _clocks.value = builtIn + loadImported()
    }

    fun delete(id: String) {
        if (id in builtInIds) return
        File(dir, "$id.svg").delete()
        _clocks.value = builtIn + loadImported()
    }

    private fun loadImported(): List<ClockDesign> =
        dir.listFiles { f -> f.extension == "svg" }.orEmpty().mapNotNull { file ->
            (ClockSvg.check(file.readText(), builtInIds, emptySet()) as? ClockCheck.Valid)?.design
        }.sortedBy { it.name.lowercase() }

    companion object {
        /** A built-in clock from the APK's `clocks/` folder; its file is trusted. */
        fun builtIn(text: String): ClockDesign =
            (ClockSvg.check(text, emptySet(), emptySet()) as ClockCheck.Valid).design.copy(custom = false)
    }
}
