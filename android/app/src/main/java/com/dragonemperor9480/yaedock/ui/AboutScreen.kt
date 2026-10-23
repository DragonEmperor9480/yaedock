package com.dragonemperor9480.yaedock.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.core.net.toUri
import com.dragonemperor9480.yaedock.R

private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/** What the app is built with, and under which licence. */
private val LIBRARIES = listOf(
    "Jetpack Compose, AndroidX, CameraX" to "Apache 2.0",
    "Kotlin, kotlinx.serialization, coroutines" to "Apache 2.0",
    "OkHttp" to "Apache 2.0",
    "ML Kit barcode scanning" to "ML Kit terms",
    "Space Grotesk, JetBrains Mono" to "SIL Open Font License 1.1",
)

/** The app's version and licence, and the PC it is connected to. */
@Composable
fun AboutScreen(connectedPc: String?, onCommands: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var libraries by remember { mutableStateOf(false) }
    val identity = @Composable { cardModifier: Modifier ->
        DockCard(cardModifier, padding = 22.dp, spacing = 14.dp) {
            AppMark(64)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("YaeDock", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Muted("Your PC's stats and controls, on your phone. A dock for any Linux PC.", size = 14.sp, maxLines = 3)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("v${appVersion()}", mono = true)
                Pill("Open source · GPL-3.0", accent = true)
            }
        }
    }
    val openSource = @Composable {
        DockCard(Modifier.fillMaxWidth(), padding = 14.dp, spacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconTile(R.drawable.ic_code)
                Column(Modifier.weight(1f)) {
                    Text("Free and open source", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Muted("Licensed under the GNU GPL v3. Use it, read the code, change it and share it.", size = 12.sp, maxLines = 3)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipButton("Licence", {
                    context.startActivity(Intent(Intent.ACTION_VIEW, LICENSE_URL.toUri()))
                }, Modifier.weight(1f), icon = painterResource(R.drawable.ic_licence))
                ChipButton("Libraries", { libraries = true }, Modifier.weight(1f), icon = painterResource(R.drawable.ic_package))
            }
        }
    }
    val privacy = @Composable { cardModifier: Modifier ->
        DockCard(cardModifier, padding = 14.dp, spacing = 8.dp) {
            IconTile(R.drawable.ic_shield)
            Text("Stays on your network", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted("Talks only to PCs you pair, over an encrypted connection. No accounts, no cloud, no tracking.", size = 12.sp, maxLines = 4)
        }
    }
    val pc = @Composable { cardModifier: Modifier ->
        DockCard(cardModifier, padding = 14.dp, spacing = 8.dp) {
            IconTile(R.drawable.ic_laptop)
            Text(connectedPc ?: "Your PC", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Muted(if (connectedPc != null) "Connected now" else "Not connected", size = 12.sp)
            ChipButton("PC commands", onCommands, Modifier.fillMaxWidth(), icon = painterResource(R.drawable.ic_terminal), height = 40.dp)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (isWide(maxWidth, maxHeight)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScreenHeader("About", "YaeDock ${appVersion()}", leading = { BackButton(onBack) })
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    identity(Modifier.weight(0.4f).fillMaxHeight())
                    Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        openSource()
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            privacy(Modifier.weight(1f).fillMaxHeight())
                            pc(Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ScreenHeader("About", "YaeDock ${appVersion()}", leading = { BackButton(onBack) })
                identity(Modifier.fillMaxWidth())
                openSource()
                privacy(Modifier.fillMaxWidth())
                pc(Modifier.fillMaxWidth())
            }
        }
    }
    if (libraries) {
        AlertDialog(
            onDismissRequest = { libraries = false },
            containerColor = YaeColors.card,
            title = { Text("Open-source libraries") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((name, licence) in LIBRARIES) {
                        Column {
                            Text(name, fontSize = 14.sp)
                            Muted(licence, size = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { libraries = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun Pill(text: String, mono: Boolean = false, accent: Boolean = false) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (accent) YaeColors.accentSoft else YaeColors.raised)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        fontFamily = if (mono) Mono else Grotesk,
        fontSize = 12.sp,
        fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
        color = if (accent) YaeColors.accent else YaeColors.text,
    )
}

/** An icon in a small rounded square, at the top of a card. */
@Composable
private fun IconTile(icon: Int) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = YaeColors.accent)
    }
}

/** This app's version name, such as `0.1.0`. */
@Composable
fun appVersion(): String {
    val context = LocalContext.current
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}
