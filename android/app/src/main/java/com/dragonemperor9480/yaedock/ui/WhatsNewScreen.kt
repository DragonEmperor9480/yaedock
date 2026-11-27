package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.update.ReleaseNotes
import com.dragonemperor9480.yaedock.update.ReleaseNotes.Block
import com.dragonemperor9480.yaedock.update.ReleaseNotes.Inline
import com.dragonemperor9480.yaedock.update.ReleaseNotes.Item
import com.dragonemperor9480.yaedock.update.ReleaseNotes.Style

/**
 * A release's notes as a page of their own: the version and its summary at the
 * top, then a card for each section (New, Fixed, Better…), with headings,
 * lists, code and links drawn as such rather than as Markdown.
 */
@Composable
fun WhatsNewScreen(version: String, notes: ReleaseNotes, onBack: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = isWide(maxWidth, maxHeight)
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (wide) 12.dp else 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader("What's new", "YaeDock $version", leading = { BackButton(onBack) })
            if (wide) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Hero(version, notes, Modifier.weight(0.4f).fillMaxHeight())
                    Column(
                        Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) { Sections(notes) }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Hero(version, notes, Modifier.fillMaxWidth())
                    Sections(notes)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** The version, large, over the release's one-line summary. */
@Composable
private fun Hero(version: String, notes: ReleaseNotes, modifier: Modifier) {
    Column(
        modifier.clip(CardShape)
            .background(Brush.linearGradient(listOf(YaeColors.accentSoft, YaeColors.card)))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("YAEDOCK", style = CardLabel, color = YaeColors.accent)
        Text(version, fontFamily = Grotesk, fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
        if (notes.summary.isNotEmpty()) {
            RichText(notes.summary, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun ColumnScope.Sections(notes: ReleaseNotes) {
    if (notes.sections.isEmpty()) {
        DockCard(Modifier.fillMaxWidth(), padding = 16.dp) {
            Muted("There are no notes for this version.", size = 14.sp, maxLines = 2)
        }
    }
    for (section in notes.sections) SectionCard(section)
}

/** How a section looks, by its title. */
private data class Look(val icon: Int, val tint: @Composable () -> Color)

private fun look(title: String): Look = when (title.lowercase()) {
    "new" -> Look(R.drawable.ic_bolt) { YaeColors.accent }
    "fixed" -> Look(R.drawable.ic_shield) { YaeColors.accent }
    "better" -> Look(R.drawable.ic_gauge) { YaeColors.accent }
    "good to know" -> Look(R.drawable.ic_info) { YaeColors.amber }
    "what the pc needs" -> Look(R.drawable.ic_laptop) { YaeColors.muted }
    "updating" -> Look(R.drawable.ic_refresh) { YaeColors.muted }
    else -> Look(R.drawable.ic_info) { YaeColors.muted }
}

@Composable
private fun SectionCard(section: ReleaseNotes.Section) {
    val look = look(section.title)
    val tint = look.tint()
    DockCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(look.icon), null, Modifier.size(18.dp), tint = tint)
            }
            Text(section.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        section.blocks.forEachIndexed { i, block ->
            // A heading opens a group, so it gets room above it.
            if (block is Block.Heading && i > 0) Spacer(Modifier.height(4.dp))
            BlockView(block)
        }
    }
}

@Composable
private fun BlockView(block: Block) {
    when (block) {
        is Block.Heading -> RichText(block.text, fontSize = 15.sp, lineHeight = 21.sp, weight = FontWeight.SemiBold)
        is Block.Paragraph -> RichText(block.text, fontSize = 14.sp, lineHeight = 21.sp, color = YaeColors.text.copy(alpha = 0.86f))
        is Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (item in block.items) ItemView(item, depth = 0)
        }
        is Block.Code -> Text(
            block.text,
            Modifier.fillMaxWidth().clip(ChipShape).background(YaeColors.background)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            fontFamily = Mono,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            softWrap = false,
        )
    }
}

@Composable
private fun ItemView(item: Item, depth: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Level with the first line of text.
            Box(Modifier.height(21.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(if (depth == 0) 6.dp else 5.dp).clip(CircleShape)
                        .background(if (depth == 0) YaeColors.accent else YaeColors.muted),
                )
            }
            RichText(item.text, fontSize = 14.sp, lineHeight = 21.sp, color = YaeColors.text.copy(alpha = 0.86f))
        }
        if (item.children.isNotEmpty()) {
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (child in item.children) ItemView(child, depth + 1)
            }
        }
    }
}

/** Styled runs as one text; links open in the browser. */
@Composable
private fun RichText(
    runs: List<Inline>,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    color: Color = Color.Unspecified,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(styled(runs), fontSize = fontSize, lineHeight = lineHeight, color = color, fontWeight = weight)
}

@Composable
private fun styled(runs: List<Inline>): AnnotatedString {
    val accent = YaeColors.accent
    val codeBackground = YaeColors.raised
    val text = YaeColors.text
    return buildAnnotatedString {
        for (run in runs) {
            when (run.style) {
                Style.PLAIN -> append(run.text)
                Style.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = text)) { append(run.text) }
                Style.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(run.text) }
                Style.CODE -> withStyle(SpanStyle(fontFamily = Mono, fontSize = 0.88.em, background = codeBackground)) {
                    append(" ${run.text} ")
                }
                Style.LINK -> withLink(
                    LinkAnnotation.Url(
                        run.url.orEmpty(),
                        TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)),
                    ),
                ) { append(run.text) }
            }
        }
    }
}
