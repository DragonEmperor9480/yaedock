package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.net.DaemonConnection.CheckAnswer
import com.dragonemperor9480.yaedock.protocol.CheckOutcome
import com.dragonemperor9480.yaedock.protocol.CheckResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where the check stands: asked for and waiting, or answered. */
private sealed interface CheckView {
    data object Running : CheckView
    data class Done(val answer: CheckAnswer) : CheckView
}

/**
 * The PC's system check, the one `yaedock check` runs: what works there, and for
 * anything missing from the app, why and how to fix it. It runs when the screen
 * opens; [connection] is null when no PC is connected.
 */
@Composable
fun CheckScreen(
    connection: DaemonConnection?,
    connected: Boolean,
    pcName: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var view by remember(connection) { mutableStateOf<CheckView?>(null) }
    var runs by remember { mutableStateOf(0) }
    LaunchedEffect(connection, connected, runs) {
        if (connection == null || !connected) return@LaunchedEffect
        view = CheckView.Running
        // Listen before asking, so a quick answer is not missed.
        launch(start = CoroutineStart.UNDISPATCHED) { view = CheckView.Done(connection.checks.first()) }
        if (!connection.runCheck()) view = CheckView.Done(CheckAnswer.Failed("Not connected to the PC"))
    }
    val again: () -> Unit = { runs++ }
    val subtitle = when {
        !connected || pcName == null -> "Not connected"
        view == CheckView.Running -> "Checking $pcName…"
        else -> "What works on $pcName"
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = isWide(maxWidth, maxHeight)
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (wide) 12.dp else 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader("PC check", subtitle, leading = { BackButton(onBack) }) {
                if (connected && view is CheckView.Done) {
                    SquareButton(painterResource(R.drawable.ic_restart), "Check again", again)
                }
            }
            val shown = view
            when {
                connection == null || !connected -> Message(
                    "Connect to a PC first",
                    "The check runs on the PC, so open YaeDock with your PC connected. On the PC itself, yaedock check does the same.",
                )
                shown == null || shown == CheckView.Running -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = YaeColors.accent)
                        Muted("Looking at $pcName; this takes a second or two", size = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
                shown is CheckView.Done -> when (val answer = shown.answer) {
                    CheckAnswer.Unsupported -> Message(
                        "Update YaeDock on the PC",
                        "$pcName runs a version of YaeDock from before the check could run from the phone. Download the latest release on the PC and run ./yaedock --install, or run yaedock check there.",
                    )
                    is CheckAnswer.Failed -> Message("The check didn't run", answer.message, again)
                    is CheckAnswer.Report -> Report(answer.report.checks, wide)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Report(checks: List<CheckResult>, wide: Boolean) {
    // What needs attention first, the worst at the top; the list keeps the check's order otherwise.
    val problems = checks.filter { it.outcome != CheckOutcome.OK }.sortedByDescending { it.outcome == CheckOutcome.FAIL }
    val working = checks.filter { it.outcome == CheckOutcome.OK }
    val summary = @Composable { Summary(checks) }
    val problemCards = @Composable {
        if (problems.isEmpty()) {
            Text(
                "Everything works. If a feature is still missing in the app, reconnect to the PC so it sees the change.",
                Modifier.fillMaxWidth().clip(ChipShape).background(YaeColors.accentSoft).padding(horizontal = 14.dp, vertical = 12.dp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        } else {
            for (check in problems) ProblemCard(check)
            Muted(
                "After fixing something, tap check again. Features that were off show up once the phone reconnects.",
                Modifier.padding(horizontal = 4.dp),
                size = 12.sp,
                maxLines = 3,
            )
        }
    }
    val workingCard = @Composable {
        if (working.isNotEmpty()) {
            DockCard(Modifier.fillMaxWidth(), padding = 14.dp, spacing = 10.dp) {
                CardHeader("Working")
                for (check in working) WorkingRow(check)
            }
        }
    }
    if (wide) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                summary()
                problemCards()
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { workingCard() }
        }
    } else {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            summary()
            problemCards()
            workingCard()
        }
    }
}

/** "16 working · 1 with limits · 1 not working", each count in its colour. */
@Composable
private fun Summary(checks: List<CheckResult>) {
    val count = { outcome: CheckOutcome -> checks.count { it.outcome == outcome } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Count(count(CheckOutcome.OK), "working", YaeColors.accent, Modifier.weight(1f))
        Count(count(CheckOutcome.WARN), "with limits", YaeColors.amber, Modifier.weight(1f))
        Count(count(CheckOutcome.FAIL), "not working", YaeColors.red, Modifier.weight(1f))
    }
}

@Composable
private fun Count(value: Int, label: String, color: Color, modifier: Modifier) {
    DockCard(modifier.semantics(mergeDescendants = true) {}, padding = 12.dp, spacing = 2.dp) {
        MonoValue(value.toString(), size = 22.sp, color = if (value == 0) YaeColors.muted else color)
        Muted(label, size = 12.sp)
    }
}

/** A warning or failure: what is wrong, what to do, and the command for it. */
@Composable
private fun ProblemCard(check: CheckResult) {
    val fail = check.outcome == CheckOutcome.FAIL
    DockCard(Modifier.fillMaxWidth(), padding = 14.dp, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Mark(check.outcome)
            Column(Modifier.weight(1f)) {
                Text(check.area, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (fail) "Not working" else "Works, with limits",
                    fontSize = 12.sp,
                    color = if (fail) YaeColors.redText else YaeColors.amberText,
                )
            }
        }
        Text(check.summary, fontSize = 14.sp, lineHeight = 20.sp)
        check.fix?.let { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = YaeColors.muted) }
        check.command?.let { command ->
            Column(Modifier.fillMaxWidth().clip(ChipShape).background(YaeColors.background).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Muted("On the PC, run", size = 11.sp)
                SelectionContainer {
                    Text(command, Modifier.padding(top = 2.dp), fontFamily = Mono, fontSize = 13.sp, color = YaeColors.accent)
                }
            }
        }
    }
}

@Composable
private fun WorkingRow(check: CheckResult) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 1.dp)) { Mark(CheckOutcome.OK, small = true) }
        Column(Modifier.weight(1f)) {
            Text(check.area, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Muted(check.summary, size = 12.sp, maxLines = 3)
        }
    }
}

/** A tick, an exclamation mark or a cross in a soft circle. */
@Composable
private fun Mark(outcome: CheckOutcome, small: Boolean = false) {
    val (background, color, label) = when (outcome) {
        CheckOutcome.OK -> Triple(YaeColors.accentSoft, YaeColors.accent, "Working")
        CheckOutcome.WARN -> Triple(YaeColors.amberSoft, YaeColors.amberText, "With limits")
        CheckOutcome.FAIL -> Triple(YaeColors.redSoft, YaeColors.redText, "Not working")
    }
    val size = if (small) 20.dp else 30.dp
    Box(
        Modifier.size(size).clip(CircleShape).background(background).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (outcome) {
            CheckOutcome.OK -> Icon(Icons.Rounded.Check, null, Modifier.size(size * 0.62f), tint = color)
            CheckOutcome.FAIL -> Icon(Icons.Rounded.Close, null, Modifier.size(size * 0.6f), tint = color)
            CheckOutcome.WARN -> Text("!", fontSize = if (small) 12.sp else 16.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/** A message in place of the results, with a way to try again. */
@Composable
private fun ColumnScope.Message(title: String, text: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(360.dp).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Muted(text, size = 13.sp, textAlign = TextAlign.Center, maxLines = 6)
            if (onRetry != null) ChipButton("Try again", onRetry, Modifier.padding(top = 8.dp).width(160.dp))
        }
    }
}
