package com.dragonemperor9480.yaedock.update

import com.dragonemperor9480.yaedock.update.ReleaseNotes.Block
import com.dragonemperor9480.yaedock.update.ReleaseNotes.Inline
import com.dragonemperor9480.yaedock.update.ReleaseNotes.Style
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val notes = """
        YaeDock 0.9.0 does **many** things.

        ## New

        ### Install with one command
        On the PC:

        ```sh
        curl -fsSL https://example.com/install.sh | sh
        ```

        It downloads YaeDock.
        - **Wi-Fi:** the band, and
          a second line.
        - **Both:**
          - nested, with `code`
        - Tap *Edit*, see [the docs](https://example.com/docs/).

        ## Fixed

        A plain paragraph
        over two lines.


        ## What's Changed
        * Release 0.9.0 by @someone in https://github.com/x/y/pull/1

        **Full Changelog**: https://github.com/x/y/compare/v0.8.0...v0.9.0
    """.trimIndent()

    @Test
    fun readsTheSummaryAndSections() {
        val parsed = ReleaseNotes.parse(notes)
        assertEquals(
            listOf(Inline("YaeDock 0.9.0 does "), Inline("many", Style.BOLD), Inline(" things.")),
            parsed.summary,
        )
        assertEquals(listOf("New", "Fixed"), parsed.sections.map { it.title })
    }

    @Test
    fun readsBlocksInOrder() {
        val blocks = ReleaseNotes.parse(notes).sections[0].blocks
        assertEquals(listOf(Inline("Install with one command")), (blocks[0] as Block.Heading).text)
        assertEquals(listOf(Inline("On the PC:")), (blocks[1] as Block.Paragraph).text)
        assertEquals("curl -fsSL https://example.com/install.sh | sh", (blocks[2] as Block.Code).text)
        assertEquals(listOf(Inline("It downloads YaeDock.")), (blocks[3] as Block.Paragraph).text)
        val items = (blocks[4] as Block.Bullets).items
        assertEquals(3, items.size)
        assertEquals(listOf(Inline("Wi-Fi:", Style.BOLD), Inline(" the band, and a second line.")), items[0].text)
        assertEquals(listOf(Inline("nested, with "), Inline("code", Style.CODE)), items[1].children.single().text)
        assertEquals(
            listOf(
                Inline("Tap "),
                Inline("Edit", Style.ITALIC),
                Inline(", see "),
                Inline("the docs", Style.LINK, "https://example.com/docs/"),
                Inline("."),
            ),
            items[2].text,
        )
    }

    @Test
    fun joinsParagraphLinesAndDropsGitHubsList() {
        val fixed = ReleaseNotes.parse(notes).sections[1]
        assertEquals(listOf(Block.Paragraph(listOf(Inline("A plain paragraph over two lines.")))), fixed.blocks)
    }

    @Test
    fun linksBareAddresses() {
        assertEquals(
            listOf(Inline("Go to "), Inline("https://example.com/a", Style.LINK, "https://example.com/a"), Inline(".")),
            ReleaseNotes.inline("Go to https://example.com/a."),
        )
    }

    @Test
    fun readsEveryReleasesNotes() {
        val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "release-notes") }.first { it.isDirectory || it.path == "/release-notes" }
        val files = dir.listFiles { f -> f.extension == "md" }.orEmpty()
        assertTrue("no release notes found in $dir", files.isNotEmpty())
        for (file in files) {
            val parsed = ReleaseNotes.parse(file.readText())
            assertTrue("${file.name} has no summary", parsed.summary.isNotEmpty())
            assertTrue("${file.name} has no sections", parsed.sections.isNotEmpty())
            // No Markdown left over as text.
            val text = parsed.sections.flatMap { s -> s.blocks }.joinToString { it.toString() }
            assertTrue("${file.name} kept a heading mark", "## " !in text)
        }
    }
}
