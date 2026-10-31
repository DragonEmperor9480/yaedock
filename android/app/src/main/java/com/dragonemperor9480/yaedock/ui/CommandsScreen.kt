package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A `yaedock` command and what it does. */
private class Command(val line: String, val does: String)

private class CommandGroup(val title: String, val commands: List<Command>)

/** The commands worth knowing, from `yaedock --help`; the developer ones are left out. */
private val GROUPS = listOf(
    CommandGroup(
        "Set up",
        listOf(
            Command("./yaedock --install", "Install and open the setup guide"),
            Command("yaedock setup", "Open the setup guide again"),
            Command("yaedock check", "See which features work here, and how to fix the rest"),
            Command("yaedock uninstall", "Remove YaeDock and its background service"),
        ),
    ),
    CommandGroup(
        "Phones",
        listOf(
            Command("yaedock pair", "Show a QR code for a new phone to scan"),
            Command("yaedock devices", "List paired phones and their ids"),
            Command("yaedock unpair ID", "Forget a phone"),
        ),
    ),
    CommandGroup(
        "Daemon",
        listOf(
            Command("yaedock status", "Whether it runs, and which phones are connected"),
            Command("yaedock service restart", "Restart it, for example after an update"),
            Command("yaedock service logs", "Follow the daemon's log"),
            Command("yaedock service enable", "Start it now and at every login; disable to stop"),
        ),
    ),
    CommandGroup(
        "Features",
        listOf(
            Command("yaedock fps on", "Game FPS on the phone through MangoHud; off to stop"),
            Command("yaedock fps hide", "Hide MangoHud's overlay on the PC; show to bring it back"),
            Command("yaedock obs on", "Screen recording through OBS Studio; off to stop"),
            Command("yaedock brightness 70", "Set the screen brightness in percent"),
            Command("yaedock volume 50", "Set the volume; or --mute, --unmute"),
        ),
    ),
    CommandGroup(
        "Shortcut buttons",
        listOf(
            Command("yaedock shortcuts", "List your buttons"),
            Command("yaedock shortcuts add NAME CMD", "Add a button that runs CMD on the PC"),
            Command("yaedock shortcuts remove NAME", "Remove a button"),
        ),
    ),
)

/** The `yaedock` commands for the PC, grouped, for looking up while at the PC. */
@Composable
fun CommandsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val tip = @Composable {
        Text(
            buildAnnotatedString {
                append("Run ")
                withStyle(SpanStyle(fontFamily = Mono, color = YaeColors.accent)) { append("yaedock") }
                append(" on its own for a menu, or add ")
                withStyle(SpanStyle(fontFamily = Mono, color = YaeColors.accent)) { append("--help") }
                append(" to any command for its options.")
            },
            Modifier.fillMaxWidth().clip(ChipShape).background(YaeColors.accentSoft).padding(horizontal = 14.dp, vertical = 12.dp),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (isWide(maxWidth, maxHeight)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScreenHeader("Commands", "Run these in a terminal on your PC", leading = { BackButton(onBack) })
                // Three columns, each scrolling on its own if the phone is short.
                val columns = listOf(GROUPS.subList(0, 2), GROUPS.subList(2, 4), GROUPS.subList(4, 5))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    columns.forEachIndexed { i, groups ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            for (group in groups) GroupCard(group)
                            if (i == columns.lastIndex) tip()
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ScreenHeader("Commands", "Run these in a terminal on your PC", leading = { BackButton(onBack) })
                tip()
                for (group in GROUPS) GroupCard(group)
            }
        }
    }
}

@Composable
private fun GroupCard(group: CommandGroup) {
    DockCard(Modifier.fillMaxWidth(), padding = 14.dp, spacing = 10.dp) {
        CardHeader(group.title)
        for (command in group.commands) {
            Column {
                Text(command.line, fontFamily = Mono, fontSize = 13.sp, color = YaeColors.accent)
                Muted(command.does, Modifier.padding(top = 2.dp), size = 12.sp, maxLines = 2)
            }
        }
    }
}
