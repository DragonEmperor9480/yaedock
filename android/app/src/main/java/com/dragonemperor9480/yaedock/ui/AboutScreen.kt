package com.dragonemperor9480.yaedock.ui

import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.dragonemperor9480.yaedock.R

private const val SOURCE_URL = "https://github.com/DragonEmperor9480/yaedock"
private const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"
private const val AUTHOR = "DragonEmperor9480"

/** What the app is built with, and under which licence. */
private val LIBRARIES = listOf(
    "Jetpack Compose, AndroidX, CameraX" to "Apache 2.0",
    "Kotlin, kotlinx.serialization, coroutines" to "Apache 2.0",
    "OkHttp" to "Apache 2.0",
    "ML Kit barcode scanning" to "ML Kit terms",
    "Space Grotesk, JetBrains Mono" to "SIL Open Font License 1.1",
)

/**
 * The app's name and version around a logo of slowly turning rings, what keeps
 * it private, links to the code and licences, and the PC it is connected to.
 * [connectedPc] is null when no PC is connected; [pcDetail] is a line about it.
 */
@Composable
fun AboutScreen(
    connectedPc: String?,
    pcDetail: String?,
    onCommands: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var libraries by remember { mutableStateOf(false) }
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    val links = listOf(
        Link("Source code", R.drawable.ic_code) { open(SOURCE_URL) },
        Link("Licence", R.drawable.ic_licence) { open(LICENSE_URL) },
        Link("Libraries", R.drawable.ic_package) { libraries = true },
        Link("PC commands", R.drawable.ic_terminal, onCommands),
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (isWide(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DockCard(Modifier.width(330.dp).fillMaxHeight(), padding = 20.dp, spacing = 0.dp) {
                    TopRow(onBack)
                    Spacer(Modifier.height(12.dp))
                    Rings(112)
                    Spacer(Modifier.height(12.dp))
                    Title(34)
                    Spacer(Modifier.height(6.dp))
                    Muted(TAGLINE, size = 13.sp, maxLines = 3)
                    Spacer(Modifier.weight(1f))
                    Badges()
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Private(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (link in links) LinkTile(link, stacked = true, Modifier.weight(1f))
                    }
                    PcCard(connectedPc, pcDetail, showAuthor = true, Modifier.fillMaxWidth().weight(1f))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopRow(onBack, card = true)
                DockCard(Modifier.fillMaxWidth(), padding = 20.dp, spacing = 0.dp) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Rings(128)
                        Spacer(Modifier.height(14.dp))
                        Title(36)
                        Spacer(Modifier.height(6.dp))
                        Muted(TAGLINE, Modifier.padding(horizontal = 20.dp), size = 14.sp, maxLines = 3, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Badges()
                    }
                }
                Private(Modifier.fillMaxWidth(), short = true)
                for (pair in links.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (link in pair) LinkTile(link, stacked = false, Modifier.weight(1f))
                    }
                }
                PcCard(connectedPc, pcDetail, showAuthor = false, Modifier.fillMaxWidth())
                Text(
                    "Made by $AUTHOR with Rust and Kotlin",
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    fontSize = 12.sp,
                    color = YaeColors.muted,
                    textAlign = TextAlign.Center,
                )
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

private const val TAGLINE = "Your PC's stats and controls on your phone. A dock for any Linux PC."

private class Link(val label: String, val icon: Int, val onClick: () -> Unit)

/** The back button and a small "About" label; on its own row in portrait. */
@Composable
private fun TopRow(onBack: () -> Unit, card: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (card) YaeColors.card else YaeColors.raised)
                .clickable(role = Role.Button, onClickLabel = "Back to Settings", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_chevron_left), "Back", Modifier.size(20.dp), tint = YaeColors.text)
        }
        Text("ABOUT", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = YaeColors.muted)
    }
}

@Composable
private fun Title(size: Int) {
    Text("YaeDock", fontSize = size.sp, lineHeight = (size * 1.05f).sp, fontWeight = FontWeight.Bold, letterSpacing = (size * -0.03f).sp)
}

@Composable
private fun Badges() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill("v${appVersion()}", mono = true)
        Pill("Open source", accent = true)
        Pill("GPL-3.0")
    }
}

/**
 * The logo: a "Y" inside three rings, like the stat gauges, each turning at
 * its own speed. With animations off in the system settings they stand still.
 */
@Composable
private fun Rings(sizeDp: Int) {
    val turning = rememberInfiniteTransition(label = "rings")
    fun spin(millis: Int) = infiniteRepeatable<Float>(tween(millis, easing = LinearEasing), RepeatMode.Restart)
    val outer by turning.animateFloat(0f, 360f, spin(18_000), label = "outer")
    val middle by turning.animateFloat(0f, -360f, spin(13_000), label = "middle")
    val inner by turning.animateFloat(0f, 360f, spin(9_000), label = "inner")
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // Drawn on a 132-unit square, as in the design.
            val unit = size.minDimension / 132f
            ring(60 * unit, 6 * unit, 220f, outer, YaeColors.accent)
            ring(47 * unit, 6 * unit, 146f, middle, YaeColors.amber)
            ring(34 * unit, 6 * unit, 252f, inner, YaeColors.accentDim)
        }
        Text("Y", fontSize = (sizeDp * 0.26f).sp, fontWeight = FontWeight.Bold, color = YaeColors.text)
    }
}

/** A track of [radius], with an arc of [sweep] degrees on it turned by [angle]. */
private fun DrawScope.ring(radius: Float, width: Float, sweep: Float, angle: Float, color: Color) {
    drawCircle(YaeColors.raised, radius, style = Stroke(width))
    rotate(angle) {
        drawArc(
            color,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

/** Why the app is safe to leave connected: three short facts. */
@Composable
private fun Private(modifier: Modifier, short: Boolean = false) {
    DockCard(modifier, padding = 14.dp, spacing = 10.dp) {
        Text("Private by design", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Fact(R.drawable.ic_wifi, if (short) "Local only" else "Your network only")
            Fact(R.drawable.ic_lock, if (short) "TLS, pinned" else "TLS, pinned key")
            Fact(R.drawable.ic_cloud_off, if (short) "No cloud" else "No accounts or cloud")
        }
    }
}

@Composable
private fun Fact(icon: Int, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(YaeColors.raised).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(14.dp), tint = YaeColors.accent)
        Text(text, fontSize = 12.sp, maxLines = 1)
    }
}

/** A link as a tile: icon over label when [stacked], side by side otherwise. */
@Composable
private fun LinkTile(link: Link, stacked: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(if (stacked) 16.dp else 18.dp)
    val base = modifier.height(if (stacked) 64.dp else 60.dp).clip(shape).background(YaeColors.card)
        .clickable(role = Role.Button, onClick = link.onClick)
    val icon = @Composable { Icon(painterResource(link.icon), null, Modifier.size(20.dp), tint = YaeColors.accent) }
    if (stacked) {
        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            icon()
            Text(link.label, fontSize = 13.sp, maxLines = 1)
        }
    } else {
        Row(base.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon()
            Text(link.label, fontSize = 14.sp, maxLines = 1)
        }
    }
}

/** The connected PC with a gently pulsing dot, or a note that none is connected. */
@Composable
private fun PcCard(name: String?, detail: String?, showAuthor: Boolean, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(YaeColors.card).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_laptop), null, Modifier.size(20.dp), tint = YaeColors.accent)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name ?: "No PC connected", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (name != null) {
                    PulsingDot()
                    Text("Connected", fontSize = 12.sp, color = YaeColors.accent, maxLines = 1)
                }
            }
            detail?.let { Text(it, fontFamily = Mono, fontSize = 12.sp, color = YaeColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (showAuthor) {
            Column(horizontalAlignment = Alignment.End) {
                Muted("Made by", size = 12.sp)
                Text(AUTHOR, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1_000), RepeatMode.Reverse), label = "alpha")
    Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(YaeColors.accent))
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

/** This app's version name, such as `0.1.0`. */
@Composable
fun appVersion(): String {
    val context = LocalContext.current
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}
