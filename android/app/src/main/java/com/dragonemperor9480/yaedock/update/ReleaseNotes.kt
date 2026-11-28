package com.dragonemperor9480.yaedock.update

/**
 * A release's notes, read from the Markdown in `release-notes/VERSION.md`
 * (or the release's page on GitHub, which starts with the same text): the
 * summary line at the top, then a section for each `##` heading.
 *
 * Only what the notes use is understood: `##` and `###` headings, paragraphs,
 * `-` lists nested by two spaces, fenced code, and inline `**bold**`,
 * `*italic*`, `` `code` `` and `[links](url)`. Anything else stays as text.
 */
data class ReleaseNotes(val summary: List<Inline>, val sections: List<Section>) {
    data class Section(val title: String, val blocks: List<Block>)

    sealed interface Block {
        /** A `###` heading. */
        data class Heading(val text: List<Inline>) : Block
        data class Paragraph(val text: List<Inline>) : Block
        data class Bullets(val items: List<Item>) : Block
        data class Code(val text: String) : Block
    }

    data class Item(val text: List<Inline>, val children: List<Item> = emptyList())

    /** A run of text with one style. */
    data class Inline(val text: String, val style: Style = Style.PLAIN, val url: String? = null)

    enum class Style { PLAIN, BOLD, ITALIC, CODE, LINK }

    val isEmpty: Boolean get() = summary.isEmpty() && sections.isEmpty()

    companion object {
        /** [markdown] as notes. GitHub's own list of pull requests, which follows the notes on a release's page, is left out. */
        fun parse(markdown: String): ReleaseNotes {
            val lines = markdown.replace("\r\n", "\n").lines()
                .takeWhile { line ->
                    val trimmed = line.trim()
                    !trimmed.equals("## What's Changed", ignoreCase = true) &&
                        !trimmed.startsWith("**Full Changelog**")
                }
            var summary: List<Block> = emptyList()
            val sections = mutableListOf<Section>()
            var title: String? = null
            val chunk = mutableListOf<String>()
            fun close() {
                val blocks = blocks(chunk)
                if (title == null) summary = blocks else sections += Section(title!!, blocks)
                chunk.clear()
            }
            var fenced = false
            for (line in lines) {
                if (line.trimStart().startsWith("```")) fenced = !fenced
                if (!fenced && line.startsWith("## ")) {
                    close()
                    title = line.removePrefix("## ").trim()
                } else {
                    chunk += line
                }
            }
            close()
            // The summary is prose; anything else above the first section joins it as text.
            val summaryText = summary.flatMap { block ->
                when (block) {
                    is Block.Paragraph -> block.text
                    is Block.Heading -> block.text
                    else -> emptyList()
                }
            }
            return ReleaseNotes(summaryText, sections.filter { it.blocks.isNotEmpty() })
        }

        private fun blocks(lines: List<String>): List<Block> {
            val blocks = mutableListOf<Block>()
            val paragraph = mutableListOf<String>()
            val bullets = mutableListOf<Pair<Int, String>>()
            fun flush() {
                if (paragraph.isNotEmpty()) blocks += Block.Paragraph(inline(paragraph.joinToString(" ")))
                if (bullets.isNotEmpty()) blocks += Block.Bullets(tree(bullets))
                paragraph.clear()
                bullets.clear()
            }
            var code: MutableList<String>? = null
            for (line in lines) {
                val trimmed = line.trim()
                if (code != null) {
                    if (trimmed.startsWith("```")) {
                        blocks += Block.Code(code.joinToString("\n").trimEnd())
                        code = null
                    } else {
                        code += line
                    }
                    continue
                }
                val bullet = BULLET.matchEntire(line)
                when {
                    trimmed.startsWith("```") -> {
                        flush()
                        code = mutableListOf()
                    }
                    trimmed.isEmpty() -> flush()
                    trimmed.startsWith("#") -> {
                        flush()
                        blocks += Block.Heading(inline(trimmed.trimStart('#').trim()))
                    }
                    bullet != null -> {
                        if (paragraph.isNotEmpty()) flush()
                        bullets += bullet.groupValues[1].length / 2 to bullet.groupValues[2]
                    }
                    // A line under a list item carries on that item.
                    bullets.isNotEmpty() -> bullets[bullets.lastIndex] = bullets.last().let { (depth, text) -> depth to "$text $trimmed" }
                    else -> paragraph += trimmed
                }
            }
            code?.let { blocks += Block.Code(it.joinToString("\n").trimEnd()) }
            flush()
            return blocks
        }

        private val BULLET = Regex("""( *)[-*] +(.*)""")

        /** Flat items with their depth, as a tree. A deeper item goes under the one before it. */
        private fun tree(flat: List<Pair<Int, String>>): List<Item> {
            fun build(from: Int, depth: Int): Pair<List<Item>, Int> {
                val items = mutableListOf<Item>()
                var i = from
                while (i < flat.size && flat[i].first >= depth) {
                    val text = inline(flat[i].second)
                    val (children, next) = if (i + 1 < flat.size && flat[i + 1].first > depth) build(i + 1, flat[i + 1].first) else emptyList<Item>() to i + 1
                    items += Item(text, children)
                    i = next
                }
                return items to i
            }
            return build(0, 0).first
        }

        /** The styled runs in one line of Markdown. */
        fun inline(text: String): List<Inline> {
            val runs = mutableListOf<Inline>()
            val plain = StringBuilder()
            fun add(run: Inline) {
                if (plain.isNotEmpty()) runs += Inline(plain.toString())
                plain.clear()
                runs += run
            }
            var i = 0
            while (i < text.length) {
                val match = INLINE.firstNotNullOfOrNull { (regex, make) -> regex.matchAt(text, i)?.let { it to make } }
                if (match != null) {
                    val (found, make) = match
                    add(make(found))
                    i = found.range.last + 1
                } else {
                    plain.append(text[i])
                    i++
                }
            }
            if (plain.isNotEmpty()) runs += Inline(plain.toString())
            return runs
        }

        private val INLINE: List<Pair<Regex, (MatchResult) -> Inline>> = listOf(
            Regex("""`([^`]+)`""") to { m -> Inline(m.groupValues[1], Style.CODE) },
            Regex("""\*\*(.+?)\*\*""") to { m -> Inline(m.groupValues[1], Style.BOLD) },
            Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""") to { m -> Inline(m.groupValues[1], Style.LINK, m.groupValues[2]) },
            Regex("""https?://[^\s)]+[^\s).,;:!?]""") to { m -> Inline(m.value, Style.LINK, m.value) },
            Regex("""\*([^*\s][^*]*?)\*""") to { m -> Inline(m.groupValues[1], Style.ITALIC) },
        )
    }
}
