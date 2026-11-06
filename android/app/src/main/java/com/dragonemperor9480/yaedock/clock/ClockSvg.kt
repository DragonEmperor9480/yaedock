package com.dragonemperor9480.yaedock.clock

import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * A clock design: an SVG drawn by YaeDock, with `{placeholders}` for the time,
 * the date and the theme's colours (see `clocks/example.svg`). [CLASSIC] is
 * the app's own clock, drawn without an SVG.
 */
data class ClockDesign(
    val id: String,
    val name: String,
    val description: String = "",
    /** The SVG with its placeholders, without comments; null for [CLASSIC]. */
    val svg: String?,
    val custom: Boolean = false,
) {
    /** Whether it shows seconds, so it is redrawn every second rather than every minute. */
    val ticksEverySecond: Boolean = svg != null && SECOND_KEYS.any { "{$it}" in svg }

    /** Width over height of its viewBox. */
    val aspect: Float = svg?.let(ClockSvg::aspect) ?: 2f

    /** Whether it draws the date itself, so the screen around it leaves its own date out. */
    val showsDate: Boolean = svg != null && ClockSvg.rootAttribute(svg, "yd:shows-date") == "true"

    companion object {
        val CLASSIC = ClockDesign("classic", "Classic", "The dock's own clock", svg = null)
        private val SECOND_KEYS = listOf("ss", "second_angle", "blink")
    }
}

/** What checking a clock file found, before anything is saved. */
sealed interface ClockCheck {
    data class Invalid(val reason: String) : ClockCheck

    /** A clock that loads; [warnings] say what will not show as drawn. */
    data class Valid(val design: ClockDesign, val file: String, val warnings: List<String>, val replaces: Boolean) : ClockCheck
}

/** The colours a clock can use by name, as `#RRGGBB` or `#AARRGGBB`. */
typealias ClockColors = Map<String, String>

object ClockSvg {
    /** Far above any real clock; a bigger file is refused before it is read. */
    const val MAX_SIZE = 256 * 1024

    /** Every colour name a clock can use, the same as a theme file's. */
    val COLOR_KEYS = listOf(
        "background", "card", "raised", "track", "text", "muted", "accent", "onAccent",
        "accentSoft", "accentDim", "amber", "amberSoft", "amberText", "red", "redSoft", "redText",
    )

    /** The placeholders that show the time; a clock needs at least one. */
    private val TIME_KEYS = listOf(
        "time", "hh", "h", "HH", "H", "mm", "hour_angle", "minute_angle", "day_angle",
        "hour_word", "minute_word", "time_words",
    )

    private val OTHER_KEYS = listOf(
        "ss", "second_angle", "blink", "ampm", "weekday", "WEEKDAY", "wd", "WD", "day", "dd",
        "month", "MONTH", "mon", "MON", "MM", "year", "greeting", "name",
    )

    private val ALL_KEYS = (TIME_KEYS + OTHER_KEYS + COLOR_KEYS).toSet()

    private val PLACEHOLDER = Regex("""\{([A-Za-z_]+)\}""")
    private val ID = Regex("[a-z0-9-]{1,32}")

    /**
     * The placeholders' values at [now]: text is escaped for XML, angles are in
     * degrees clockwise from 12 o'clock. [seconds] moves the minute hand on
     * each second as well, for clocks that are redrawn every second.
     */
    fun values(
        now: LocalDateTime,
        twentyFourHour: Boolean,
        locale: Locale,
        name: String,
        greeting: String,
        colors: ClockColors,
        seconds: Boolean,
    ): Map<String, String> {
        val hour12 = (now.hour + 11) % 12 + 1
        val shownHour = if (twentyFourHour) now.hour else hour12
        val second = if (seconds) now.second else 0
        val words = timeWords(now.hour, now.minute)
        // Digits stay 0 to 9 whatever the phone's language.
        fun two(n: Int) = "%02d".format(Locale.ROOT, n)
        val text = mapOf(
            "time" to (if (twentyFourHour) two(now.hour) else "$hour12") + ":" + two(now.minute),
            "hh" to two(shownHour),
            "h" to "$shownHour",
            "HH" to two(now.hour),
            "H" to "${now.hour}",
            "mm" to two(now.minute),
            "ss" to two(now.second),
            "ampm" to if (twentyFourHour) "" else if (now.hour < 12) "AM" else "PM",
            "hour_word" to words.first,
            "minute_word" to words.second,
            "time_words" to "${words.first} ${words.second}",
            "weekday" to now.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
            "wd" to now.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
            "day" to "${now.dayOfMonth}",
            "dd" to two(now.dayOfMonth),
            "month" to now.month.getDisplayName(TextStyle.FULL, locale),
            "mon" to now.month.getDisplayName(TextStyle.SHORT, locale),
            "MM" to two(now.monthValue),
            "year" to "${now.year}",
            "greeting" to greeting,
            "name" to name.trim(),
        ).let { base ->
            base + listOf("weekday", "wd", "month", "mon").associate { it.uppercase() to base.getValue(it).uppercase(locale) }
        }.mapValues { escape(it.value) }
        val minutes = now.minute + second / 60f
        val angles = mapOf(
            "hour_angle" to (now.hour % 12) * 30f + minutes * 0.5f,
            "minute_angle" to minutes * 6f,
            "second_angle" to now.second * 6f,
            "day_angle" to (now.hour * 60 + minutes) / 4f,
        ).mapValues { "%.2f".format(Locale.ROOT, it.value) }
        return text + angles + ("blink" to if (now.second % 2 == 0) "1" else "0") + colors
    }

    /** [template] with each known placeholder replaced; unknown ones stay as written. */
    fun fill(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match -> values[match.groupValues[1]] ?: match.value }

    /** `nine` and `forty-one`, `o'clock` or `oh five`, on a 12-hour dial. */
    fun timeWords(hour: Int, minute: Int): Pair<String, String> {
        val hourWord = NUMBERS[(hour + 11) % 12 + 1]
        val minuteWord = when {
            minute == 0 -> "o'clock"
            minute < 10 -> "oh ${NUMBERS[minute]}"
            minute < 20 -> NUMBERS[minute]
            minute % 10 == 0 -> TENS[minute / 10]
            else -> "${TENS[minute / 10]}-${NUMBERS[minute % 10]}"
        }
        return hourWord to minuteWord
    }

    private val NUMBERS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty")

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    /** An attribute of the `<svg>` element, such as `yd:name`; null when it is not there. */
    fun rootAttribute(svg: String, name: String): String? {
        val root = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE).find(withoutComments(svg))?.value ?: return null
        return Regex("""\s${Regex.escape(name)}\s*=\s*(["'])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
            .find(root)?.groupValues?.get(2)?.let(::unescape)
    }

    /** Width over height of the viewBox, or null without a usable one. */
    fun aspect(svg: String): Float? {
        val box = rootAttribute(svg, "viewBox") ?: return null
        val parts = box.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4 || parts[2] <= 0f || parts[3] <= 0f) return null
        return parts[2] / parts[3]
    }

    /**
     * Reads [text] as a clock without drawing it. Whether it draws is checked
     * on the phone afterwards, as only the drawing library can tell.
     */
    fun check(file: String, builtInIds: Set<String>, importedIds: Set<String>): ClockCheck {
        if (file.length > MAX_SIZE) return ClockCheck.Invalid("This file is too big to be a clock (the limit is 256 KB)")
        // What the drawing reads: comments may explain anything, as example.svg does.
        val text = withoutComments(file)
        if (!Regex("""<svg\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return ClockCheck.Invalid("This is not an SVG file")
        }
        if (Regex("""<!(DOCTYPE|ENTITY)""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return ClockCheck.Invalid("Clocks can't declare a DOCTYPE or entities; remove the <!DOCTYPE …> line")
        }
        if (Regex("""<script\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return ClockCheck.Invalid("Clocks can't contain scripts")
        }
        if (Regex("""<foreignObject\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return ClockCheck.Invalid("Clocks can't contain HTML (foreignObject)")
        }
        val outside = Regex("""href\s*=\s*["'](?!#|data:image/)""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""url\(\s*["']?(?!#)""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""@import""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (outside) {
            return ClockCheck.Invalid("Clocks must be one file: links to other files or the web aren't allowed (images can be data: URLs)")
        }
        val id = rootAttribute(text, "yd:id")
            ?: return ClockCheck.Invalid("The <svg> element needs a yd:id, such as yd:id=\"my-clock\"")
        if (!ID.matches(id)) return ClockCheck.Invalid("yd:id must be 1 to 32 lowercase letters, digits or dashes")
        if (id in builtInIds) return ClockCheck.Invalid("\"$id\" is the id of a built-in clock; give yours its own id")
        val name = rootAttribute(text, "yd:name")?.trim()
            ?: return ClockCheck.Invalid("The <svg> element needs a yd:name, such as yd:name=\"My clock\"")
        if (name.isEmpty() || name.length > 32) return ClockCheck.Invalid("yd:name must be 1 to 32 characters")
        if (!Regex("""xmlns:yd\s*=""").containsMatchIn(text)) {
            return ClockCheck.Invalid("The <svg> element needs xmlns:yd=\"https://yaedock.amrutlabs.in/clock\" for its yd: attributes")
        }
        if (aspect(text) == null) return ClockCheck.Invalid("The <svg> element needs a viewBox, such as viewBox=\"0 0 400 200\"")
        val used = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()
        if (used.none { it in TIME_KEYS }) {
            return ClockCheck.Invalid("This clock never shows the time; use {time}, {hh} and {mm}, or the hand angles")
        }
        val warnings = buildList {
            val unknown = used - ALL_KEYS
            if (unknown.isNotEmpty()) add("Unknown placeholders show as written: " + unknown.sorted().joinToString { "{$it}" })
            if (Regex("""<(animate|animateTransform|animateMotion|set)\b""").containsMatchIn(text)) {
                add("SVG animations don't play; the placeholders still update")
            }
            if (Regex("""<filter\b""").containsMatchIn(text)) add("Filters (blur, glow, shadow) aren't drawn")
        }
        val description = rootAttribute(text, "yd:description")?.trim()?.take(80).orEmpty()
        return ClockCheck.Valid(
            ClockDesign(id, name, description, text, custom = true),
            file,
            warnings,
            replaces = id in importedIds,
        )
    }

    /** [svg] without its `<!-- -->` comments; an unclosed one stays, and is checked like the rest. */
    fun withoutComments(svg: String): String = COMMENT.replace(svg, "")

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

    private fun unescape(text: String) = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
