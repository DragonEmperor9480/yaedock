package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.pairing.PairedPc

/** The paired PCs to connect to, or the first-launch guide while there are none. */
@Composable
fun DevicesScreen(
    pcs: List<PairedPc>,
    onConnect: (PairedPc) -> Unit,
    onForget: (PairedPc) -> Unit,
    onScan: () -> Unit,
    onEnterManually: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var forgetting by remember { mutableStateOf<PairedPc?>(null) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = isWide(maxWidth, maxHeight)
        if (pcs.isEmpty()) {
            Welcome(wide, onScan, onEnterManually)
        } else {
            PcList(pcs, wide, onConnect, { forgetting = it }, onScan, onEnterManually, onSettings)
        }
    }
    forgetting?.let { pc ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            containerColor = YaeColors.card,
            title = { Text("Forget ${pc.name}?") },
            text = {
                Text(
                    "You will need to pair again to reconnect. To revoke this phone on the PC too, run `yaedock unpair` there.",
                    color = YaeColors.muted,
                )
            },
            confirmButton = { TextButton(onClick = { onForget(pc); forgetting = null }) { Text("Forget", color = YaeColors.redText) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

/** First launch: what the app is, and the three steps to pair a PC. */
@Composable
private fun Welcome(wide: Boolean, onScan: () -> Unit, onEnterManually: () -> Unit) {
    val pitch = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppMark(if (wide) 56 else 64)
            Text(
                "Turn this phone into a dock for your PC",
                fontSize = if (wide) 30.sp else 32.sp,
                lineHeight = if (wide) 33.sp else 35.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
            )
            Muted(
                "Live stats, game FPS, media, volume and power controls for your Linux PC, over your own Wi-Fi.",
                size = if (wide) 14.sp else 15.sp,
                maxLines = 4,
            )
        }
    }
    val steps = @Composable { stepsModifier: Modifier, buttons: Boolean ->
        DockCard(stepsModifier, padding = 20.dp, spacing = 14.dp) {
            Text("Pair your first PC", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Step(1, withCode("On your PC, run ", "./yaedock --install", ". The setup guide starts the daemon for you."))
            Step(2, withCode("Choose “Pair your phone” in the guide, or run ", "yaedock pair", "."))
            Step(3, AnnotatedString("Scan the QR code it shows. Your phone and PC need to be on the same Wi-Fi."))
            if (buttons) {
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScanButton(onScan, Modifier.weight(1f))
                    ChipButton("Enter details by hand", onEnterManually, height = 50.dp)
                }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(330.dp).fillMaxHeight().padding(vertical = 8.dp)) {
                pitch()
                Spacer(Modifier.weight(1f))
                Muted("Free and open source · GPL-3.0", size = 12.sp)
            }
            steps(Modifier.weight(1f).fillMaxHeight(), true)
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp)) {
            pitch()
            Spacer(Modifier.height(22.dp))
            steps(Modifier.fillMaxWidth(), false)
            Spacer(Modifier.height(28.dp))
            ScanButton(onScan, Modifier.fillMaxWidth(), height = 54)
            Spacer(Modifier.height(8.dp))
            ChipButton("Enter details by hand", onEnterManually, Modifier.fillMaxWidth(), height = 54.dp, colors = YaeColors.card to YaeColors.text)
            Muted(
                "Free and open source · GPL-3.0",
                Modifier.fillMaxWidth().padding(top = 12.dp),
                size = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** "On your PC, run `./yaedock --install`. …" with the command in mint monospace. */
private fun withCode(before: String, code: String, after: String) = buildAnnotatedString {
    append(before)
    withStyle(SpanStyle(fontFamily = Mono, fontSize = 12.sp, color = YaeColors.accent, background = YaeColors.raised)) {
        append(" $code ")
    }
    append(after)
}

@Composable
private fun Step(number: Int, text: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
            Text("$number", fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.accent)
        }
        Text(text, Modifier.weight(1f).padding(top = 3.dp), fontSize = 14.sp, lineHeight = 21.sp)
    }
}

/** The mint "Scan QR code" button. */
@Composable
fun ScanButton(onScan: () -> Unit, modifier: Modifier = Modifier, height: Int = 50) {
    Row(
        modifier.height(height.dp).clip(RoundedCornerShape(16.dp)).background(YaeColors.accent)
            .clickable(role = Role.Button, onClick = onScan),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_scan), null, Modifier.size(20.dp), tint = YaeColors.onAccent)
        Text("Scan QR code", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.onAccent)
    }
}

@Composable
private fun PcList(
    pcs: List<PairedPc>,
    wide: Boolean,
    onConnect: (PairedPc) -> Unit,
    onForget: (PairedPc) -> Unit,
    onScan: () -> Unit,
    onEnterManually: () -> Unit,
    onSettings: () -> Unit,
) {
    val header = @Composable {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppMark(40)
            Column(Modifier.weight(1f)) {
                Text("Your PCs", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Muted("Tap a PC to open its dock", size = 12.sp)
            }
            SquareButton(settingsIcon(), "Settings", onSettings)
        }
    }
    val cards = @Composable { for (pc in pcs) PcCard(pc, { onConnect(pc) }, { onForget(pc) }) }
    val add = @Composable { addModifier: Modifier, fill: Boolean ->
        DockCard(addModifier, padding = 18.dp, spacing = 10.dp) {
            Text("Add a PC", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                withCode("Run ", "yaedock pair", " on the other PC, then scan the code it shows."),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = YaeColors.muted,
            )
            if (fill) Spacer(Modifier.weight(1f))
            ScanButton(onScan, Modifier.fillMaxWidth(), height = 48)
            ChipButton("Enter details by hand", onEnterManually, Modifier.fillMaxWidth(), height = 48.dp)
        }
    }
    if (wide) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            header()
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards()
                }
                add(Modifier.width(270.dp).fillMaxHeight(), true)
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            header()
            Spacer(Modifier.height(4.dp))
            cards()
            Spacer(Modifier.height(16.dp))
            add(Modifier.fillMaxWidth(), false)
        }
    }
}

@Composable
private fun PcCard(pc: PairedPc, onConnect: () -> Unit, onForget: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(YaeColors.card).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 80.dp).clickable(role = Role.Button, onClickLabel = "Connect to ${pc.name}", onClick = onConnect)
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(24.dp), tint = YaeColors.accent)
            }
            Column(Modifier.weight(1f)) {
                Text(pc.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pc.host, fontFamily = Mono, fontSize = 12.sp, color = YaeColors.muted, maxLines = 1)
            }
            Text(
                "Connect",
                Modifier.clip(ChipShape).background(YaeColors.accent).padding(horizontal = 14.dp, vertical = 9.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = YaeColors.onAccent,
            )
        }
        SquareButton(painterResource(R.drawable.ic_trash), "Forget ${pc.name}", onForget, background = YaeColors.card, tint = YaeColors.muted)
    }
}
