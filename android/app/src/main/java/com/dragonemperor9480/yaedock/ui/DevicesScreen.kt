package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.pairing.PairedPc

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
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YaeDock", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Your PC's stats and controls, on your phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }

            if (pcs.isEmpty()) {
                Welcome(onScan, onEnterManually)
            } else {
                Text("Your PCs", style = MaterialTheme.typography.titleMedium)
                for (pc in pcs) PcCard(pc, onConnect = { onConnect(pc) }, onForget = { forgetting = pc })
                Spacer(Modifier.height(8.dp))
                Text("Add another PC", style = MaterialTheme.typography.titleMedium)
                PairButtons(onScan, onEnterManually)
            }
        }
    }

    forgetting?.let { pc ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${pc.name}?") },
            text = {
                Text("You will need to pair again to reconnect. To revoke this phone on the PC too, run `yaedock unpair` there.")
            },
            confirmButton = {
                TextButton(onClick = { onForget(pc); forgetting = null }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

/** First-run guide, shown until a PC is paired. */
@Composable
private fun Welcome(onScan: () -> Unit, onEnterManually: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            IconBadge(R.drawable.ic_computer)
            Text("Pair your first PC", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Step(1, "On your PC, run `yaedock` and choose “Start the daemon”.")
            Step(2, "Choose “Pair a phone” there (or run `yaedock pair`).")
            Step(3, "Scan the QR code it shows. Your phone and PC need to be on the same Wi-Fi.")
            PairButtons(onScan, onEnterManually)
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PairButtons(onScan: () -> Unit, onEnterManually: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_qr), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan QR code")
        }
        OutlinedButton(onClick = onEnterManually, modifier = Modifier.fillMaxWidth()) {
            Text("Enter details by hand")
        }
    }
}

@Composable
private fun PcCard(pc: PairedPc, onConnect: () -> Unit, onForget: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onConnect),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(R.drawable.ic_computer)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pc.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Tap to connect · ${pc.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForget) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Forget ${pc.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** An icon in a tinted circle. */
@Composable
private fun IconBadge(icon: Int) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
