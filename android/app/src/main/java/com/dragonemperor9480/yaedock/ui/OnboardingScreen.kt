package com.dragonemperor9480.yaedock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import kotlinx.coroutines.launch

private const val PAGES = 5
private const val NAME_PAGE = PAGES - 1
private const val MAX_NAME = 24
private val FooterHeight = 80.dp
/** In landscape the dots sit beside the button, so the footer is just the button. */
private val FooterHeightWide = 56.dp
private val TopHeight = 52.dp

/**
 * The first-run welcome: what YaeDock is, what it shows, what it controls and
 * why it is private, then the user's name for the dock's greeting. Swipe or
 * tap through; [onFinish] gets the name, which cannot be left empty.
 */
@Composable
fun OnboardingScreen(initialName: String, onFinish: (String) -> Unit, modifier: Modifier = Modifier) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(initialName) }
    val page = pager.currentPage
    val finish = { if (name.isNotBlank()) onFinish(name.trim()) }
    // Both read the page when tapped, so a tap during a slide goes from where the pager is.
    val next = {
        val current = pager.currentPage
        if (current == NAME_PAGE) finish() else scope.launch { pager.animateScrollToPage(current + 1) }
    }
    val back = { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }
    BackHandler(enabled = page > 0) { back() }

    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val wide = isWide(maxWidth, maxHeight)
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { index ->
            when (index) {
                0 -> Page(wide, centred = true, art = { FoxGauge(it) }) { WelcomeText() }
                1 -> Page(wide, art = { StatsPreview(it, compact = wide) }) {
                    PageText("Your PC,\nat a glance", "CPU, GPU, memory, temperatures and network, live on your phone. Stand it next to your monitor and it becomes a dock.")
                }
                2 -> Page(wide, art = { ControlsPreview(it, compact = wide) }) {
                    PageText("Control it\nwithout alt-tab", "Change brightness and volume, see your game's frame rate, start a recording or lock the PC, right from the phone.")
                }
                // In landscape the title moves under the shield, so the three facts fit beside it.
                3 -> if (wide) {
                    Page(wide, art = {
                        Column(it, horizontalAlignment = Alignment.CenterHorizontally) {
                            Shield(Modifier, 150.dp)
                            Spacer(Modifier.height(12.dp))
                            Title(PRIVATE_TITLE, TextAlign.Center)
                        }
                    }) { Facts(compact = true) }
                } else {
                    Page(wide, art = { Shield(it) }) {
                        Title(PRIVATE_TITLE)
                        Spacer(Modifier.height(18.dp))
                        Facts(compact = false)
                    }
                }
                else -> NamePage(wide, name, onNameChange = { if (it.length <= MAX_NAME) name = it }, onDone = finish)
            }
        }
        if (page > 0) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(YaeColors.card)
                    .clickable(role = Role.Button, onClickLabel = "Previous page") { back() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_chevron_left), "Back", Modifier.size(20.dp), tint = YaeColors.text)
            }
        }
        Footer(
            page = page,
            label = when (page) {
                0 -> "Let's go"
                NAME_PAGE -> "Connect my PC"
                else -> "Next"
            },
            enabled = page != NAME_PAGE || name.isNotBlank(),
            onClick = { next() },
            wide = wide,
            modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(if (wide) 0.5f else 1f)
                .padding(start = if (wide) 10.dp else 0.dp),
        )
    }
}

/**
 * One page: the picture above its text, or beside it in landscape. Leaves room
 * for the back button and the footer, which stay put while pages slide. In
 * landscape the back button sits over the picture, so the text goes up to the top.
 */
@Composable
private fun Page(
    wide: Boolean,
    centred: Boolean = false,
    art: @Composable (Modifier) -> Unit,
    text: @Composable ColumnScope.() -> Unit,
) {
    val align = if (centred) Alignment.CenterHorizontally else Alignment.Start
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = TopHeight), contentAlignment = Alignment.Center) { art(Modifier) }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(bottom = FooterHeightWide + 16.dp),
                horizontalAlignment = align,
                verticalArrangement = Arrangement.Center,
                content = text,
            )
        }
    } else {
        Column(Modifier.fillMaxSize().padding(top = TopHeight, bottom = FooterHeight + 24.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { art(Modifier) }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = align, content = text)
        }
    }
}

@Composable
private fun WelcomeText() {
    Text(
        "WELCOME TO",
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.8.sp,
        color = FoxColors.pink,
    )
    Spacer(Modifier.height(4.dp))
    Text("YaeDock", fontSize = 46.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.6).sp)
    Spacer(Modifier.height(14.dp))
    Text(
        "Your Linux PC's stats and controls, on the phone beside you.",
        Modifier.width(290.dp),
        fontSize = 16.sp,
        lineHeight = 23.sp,
        color = YaeColors.muted,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Title(text: String, align: TextAlign = TextAlign.Start) {
    Text(text, fontSize = 32.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, textAlign = align)
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, modifier, fontSize = 16.sp, lineHeight = 23.sp, color = YaeColors.muted, textAlign = align)
}

@Composable
private fun PageText(title: String, body: String) {
    Title(title)
    Spacer(Modifier.height(12.dp))
    Body(body)
}

private const val PRIVATE_TITLE = "Stays between\nyou and your PC"

/** Why it is private, as three cards; [compact] ones fit beside the shield in landscape. */
@Composable
private fun Facts(compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Fact(R.drawable.ic_wifi, "Your Wi-Fi only", "The phone talks straight to your PC. Nothing leaves your network.", compact)
        Fact(R.drawable.ic_lock, "Encrypted and paired", "TLS on every connection, and the app only trusts PCs you pair with.", compact)
        Fact(R.drawable.ic_cloud_off, "No account, no cloud", "No sign-up and no tracking. It's open source, so you can check.", compact)
    }
}

@Composable
private fun Fact(icon: Int, title: String, text: String, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(if (compact) 16.dp else 20.dp)).background(YaeColors.card)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 9.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
    ) {
        val badge = if (compact) 32.dp else 38.dp
        Box(Modifier.size(badge).clip(RoundedCornerShape(10.dp)).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(badge / 2), tint = YaeColors.accent)
        }
        Column {
            Text(title, fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text,
                Modifier.padding(top = 2.dp),
                fontSize = if (compact) 12.sp else 13.sp,
                lineHeight = if (compact) 16.sp else 18.sp,
                color = YaeColors.muted,
            )
        }
    }
}

/** The page dots and the main button, fixed at the bottom: dots above it, or beside it when [wide]. */
@Composable
private fun Footer(page: Int, label: String, enabled: Boolean, onClick: () -> Unit, wide: Boolean, modifier: Modifier) {
    val dots = @Composable {
        Row(
            Modifier.semantics { contentDescription = "Page ${page + 1} of $PAGES" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(PAGES) { index ->
                val width by animateDpAsState(if (index == page) 22.dp else 6.dp, label = "dot")
                Box(
                    Modifier.size(width, 6.dp).clip(CircleShape)
                        .background(if (index == page) FoxColors.pink else YaeColors.track),
                )
            }
        }
    }
    val button = @Composable { buttonModifier: Modifier ->
        Row(
            buttonModifier.height(56.dp).clip(RoundedCornerShape(18.dp))
                .background(if (enabled) YaeColors.accent else YaeColors.raised)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val color = if (enabled) YaeColors.onAccent else YaeColors.muted
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = color)
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(18.dp), tint = color)
        }
    }
    if (wide) {
        Row(modifier.height(FooterHeightWide), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            dots()
            button(Modifier.weight(1f))
        }
    } else {
        Column(modifier.height(FooterHeight), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            dots()
            button(Modifier.fillMaxWidth())
        }
    }
}

/** The last page: a fox, the name field, and how the dock will greet them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamePage(wide: Boolean, name: String, onNameChange: (String) -> Unit, onDone: () -> Unit) {
    // With the keyboard up there is no room for the fox; the field matters more.
    val typing = WindowInsets.isImeVisible
    val form = @Composable { modifier: Modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            Text(
                "YOUR NAME",
                Modifier.padding(start = 4.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = YaeColors.muted,
            )
            NameField(name, onNameChange, onDone)
            Spacer(Modifier.height(10.dp))
            GreetingPreview(name)
        }
    }
    val heading = @Composable { align: TextAlign ->
        Title("What should\nwe call you?", align)
        Spacer(Modifier.height(10.dp))
        Body("The dock greets you by name. It stays on this phone.", align = align)
    }
    if (wide) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (!typing) {
                    TiltingFox(Modifier.size(96.dp))
                    Spacer(Modifier.height(14.dp))
                }
                heading(TextAlign.Center)
            }
            form(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(bottom = FooterHeightWide + 16.dp),
            )
        }
    } else {
        Column(
            Modifier.fillMaxSize().padding(top = TopHeight, bottom = FooterHeight + 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!typing) {
                TiltingFox(Modifier.size(112.dp))
                Spacer(Modifier.height(18.dp))
            }
            heading(TextAlign.Center)
            Spacer(Modifier.height(26.dp))
            form(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NameField(name: String, onNameChange: (String) -> Unit, onDone: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Your name" },
        singleLine = true,
        textStyle = TextStyle(fontFamily = Grotesk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.text),
        cursorBrush = SolidColor(YaeColors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = interaction,
        decorationBox = { field ->
            Box(
                Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(18.dp)).background(YaeColors.card)
                    .border(2.dp, if (focused) YaeColors.accent else YaeColors.track, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (name.isEmpty()) {
                    Text("Your name", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.muted)
                }
                field()
            }
        },
    )
}

/** "Good evening, Alex" as Home will show it, next to the time. */
@Composable
private fun GreetingPreview(name: String) {
    val clock = rememberClock()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(YaeColors.card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("ON YOUR DOCK", style = CardLabel)
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    append(greeting(clock.hour, ""))
                    if (name.isNotBlank()) {
                        append(", ")
                        withStyle(SpanStyle(color = FoxColors.pink)) { append(name.trim()) }
                    }
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
            )
        }
        Text(clock.time, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp, color = YaeColors.muted)
    }
}

// The pictures.

/** The fox on its own, tilting its head now and then and blinking. */
@Composable
private fun TiltingFox(modifier: Modifier) {
    val motion = rememberInfiniteTransition(label = "tilt")
    val tilt by motion.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 5000
        0f at 3500
        -7f at 3900
        5f at 4300
        -2f at 4650
        0f at 5000
    }), label = "tilt")
    val blink by motion.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 4000
        1f at 3680
        0.15f at 3840
        1f at 4000
    }), label = "blink")
    Canvas(modifier.graphicsLayer { rotationZ = tilt; transformOrigin = TransformOrigin(0.5f, 0.8f) }) {
        // The face spans 28..80 across and 26..80 down.
        scale(size.minDimension / 54f, pivot = Offset.Zero) {
            translate(-28f, -26f) { drawFox(blink) }
        }
    }
}

/** A small dock: the real time (unless [compact]), three rings that drift like live values, and network traffic. */
@Composable
private fun StatsPreview(modifier: Modifier, compact: Boolean) {
    val clock = rememberClock()
    val motion = rememberInfiniteTransition(label = "stats")
    fun drift(millis: Int) = infiniteRepeatable<Float>(tween(millis, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    val cpu by motion.animateFloat(38f, 51f, drift(2500), label = "cpu")
    val gpu by motion.animateFloat(61f, 48f, drift(3000), label = "gpu")
    val memory by motion.animateFloat(58f, 60f, drift(3500), label = "memory")
    val drawn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { drawn.animateTo(1f, tween(2200, easing = FastOutSlowInEasing)) }

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(YaeColors.card).padding(if (compact) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        // Too tall beside the text in landscape; the rings say enough there.
        if (!compact) Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(clock.time, fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.2).sp)
                Muted(clock.date, size = 13.sp)
            }
            LivePill()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ring = if (compact) 48.dp else 60.dp
            Stat("CPU", "54 °C", cpu, YaeColors.accent, ring, Modifier.weight(1f))
            Stat("GPU", "66 °C", gpu, YaeColors.amber, ring, Modifier.weight(1f))
            Stat("Memory", "9.3 of 16 GiB", memory, FoxColors.pink, ring, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(YaeColors.background)
                .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("Network", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("↓ 2.4 MB/s · ↑ 180 KB/s", fontFamily = Mono, fontSize = 11.sp, color = YaeColors.muted)
            }
            val colors = YaeColors
            Canvas(Modifier.weight(1f).height(32.dp)) {
                val points = listOf(26f, 24f, 27f, 18f, 21f, 12f, 16f, 9f, 14f, 6f, 11f, 8f, 10f)
                val step = size.width / (points.size - 1)
                val line = Path().apply {
                    points.forEachIndexed { i, y ->
                        val p = Offset(i * step, y / 34f * size.height)
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                clipRect(right = size.width * drawn.value) {
                    drawPath(line, colors.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

@Composable
private fun LivePill() {
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(1f, 0.35f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "alpha")
    Row(
        Modifier.clip(CircleShape).background(YaeColors.accentSoft).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).alpha(alpha).clip(CircleShape).background(YaeColors.accent))
        Text("Live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.accent)
    }
}

@Composable
private fun Stat(label: String, detail: String, percent: Float, color: Color, ring: Dp, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(YaeColors.background).padding(vertical = ring / 5, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ring / 10),
    ) {
        ValueRing(percent, ring, color = color)
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(detail, fontFamily = Mono, fontSize = 10.sp, color = YaeColors.muted, maxLines = 1)
    }
}

/** Brightness and volume sliding on their own, and three of the PC's controls. */
@Composable
private fun ControlsPreview(modifier: Modifier, compact: Boolean) {
    val motion = rememberInfiniteTransition(label = "controls")
    val brightness by motion.animateFloat(0.72f, 0.46f, infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "brightness")
    val volume by motion.animateFloat(0.45f, 0.68f, infiniteRepeatable(tween(3500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "volume")
    val recording by motion.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "recording")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FakeSlider(R.drawable.ic_brightness, "Brightness", "Screen", brightness, YaeColors.amber, compact)
        FakeSlider(R.drawable.ic_volume, "Volume", "Speakers", volume, YaeColors.accent, compact)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Game FPS", YaeColors.accentSoft, compact, Modifier.weight(1f)) {
                Text("144", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.accent)
            }
            Tile("Record", FoxColors.pinkSoft, compact, Modifier.weight(1f)) {
                Box(Modifier.size(14.dp).alpha(recording).clip(CircleShape).background(FoxColors.pink))
            }
            Tile("Lock PC", YaeColors.raised, compact, Modifier.weight(1f)) {
                Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(20.dp), tint = YaeColors.text)
            }
        }
    }
}

@Composable
private fun FakeSlider(icon: Int, label: String, detail: String, fraction: Float, color: Color, compact: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(YaeColors.card).padding(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
            Text(label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontFamily = Mono, fontSize = 12.sp, color = YaeColors.muted)
        }
        Box(Modifier.fillMaxWidth().height(if (compact) 10.dp else 14.dp).clip(CircleShape).background(YaeColors.track)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun Tile(label: String, badge: Color, compact: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(YaeColors.card)
            .padding(vertical = if (compact) 8.dp else 14.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        Box(Modifier.size(if (compact) 32.dp else 40.dp).clip(RoundedCornerShape(12.dp)).background(badge), contentAlignment = Alignment.Center) { content() }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A shield with two soft ripples spreading from it, in a square of [size]. */
@Composable
private fun Shield(modifier: Modifier, size: Dp = 200.dp) {
    val motion = rememberInfiniteTransition(label = "ripple")
    val first by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "first")
    val ripple = @Composable { progress: Float ->
        Box(
            Modifier.size(size * 0.75f).graphicsLayer {
                val scale = 0.7f + 0.65f * progress
                scaleX = scale
                scaleY = scale
                alpha = 0.9f * (1f - progress)
            }.border(2.dp, YaeColors.accentDim, CircleShape),
        )
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        ripple(first)
        ripple((first + 0.5f) % 1f)
        Box(Modifier.size(size * 0.52f).clip(RoundedCornerShape(size * 0.16f)).background(YaeColors.card), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(size * 0.26f), tint = YaeColors.accent)
        }
    }
}

