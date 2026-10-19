package com.dragonemperor9480.yaedock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
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
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("YaeDock", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
        }
        Spacer(Modifier.height(16.dp))
        if (pcs.isEmpty()) {
            Text(
                "No paired PCs yet. Run `yaedockd pair` on your PC, then scan the code it shows.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pcs, key = { it.fingerprint }) { pc ->
                Card(Modifier.fillMaxWidth().clickable { onConnect(pc) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, style = MaterialTheme.typography.titleMedium)
                            Text("${pc.host}:${pc.port}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { forgetting = pc }) {
                            Icon(Icons.Default.Delete, contentDescription = "Forget ${pc.name}")
                        }
                    }
                }
            }
        }
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
        OutlinedButton(onClick = onEnterManually, modifier = Modifier.fillMaxWidth()) {
            Text("Enter details by hand")
        }
    }

    forgetting?.let { pc ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${pc.name}?") },
            text = { Text("You will need to pair again to reconnect. To revoke this phone on the PC too, run `yaedockd unpair`.") },
            confirmButton = {
                TextButton(onClick = { onForget(pc); forgetting = null }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}
