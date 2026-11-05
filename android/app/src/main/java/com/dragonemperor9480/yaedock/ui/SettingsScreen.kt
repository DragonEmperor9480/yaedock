package com.dragonemperor9480.yaedock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.requiredSize
import com.dragonemperor9480.yaedock.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonemperor9480.yaedock.ThemeCheck
import com.dragonemperor9480.yaedock.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.DockSettings
import com.dragonemperor9480.yaedock.KeepScreenOn
import com.dragonemperor9480.yaedock.NavBarMode
import com.dragonemperor9480.yaedock.R
import com.dragonemperor9480.yaedock.protocol.GpuInfo

private const val MAX_NAME_LENGTH = 24
private val DIM_DELAYS = listOf(1, 2, 5, 10, 15)
private val KEEP_ON_OPTIONS = listOf(
    KeepScreenOn.WHILE_CHARGING to "While charging",
    KeepScreenOn.ALWAYS to "Always",
    KeepScreenOn.NEVER to "Never",
)
private val NAV_BAR_OPTIONS = listOf(
    NavBarMode.ALWAYS to "Always",
    NavBarMode.PORTRAIT to "Portrait",
    NavBarMode.LANDSCAPE to "Landscape",
    NavBarMode.ON_TAP to "On tap",
)

/** The settings grouped into pages: side by side in landscape, opened from a list in portrait. */
private enum class Page(val title: String, val icon: Int) {
    Dock("Dock & screen", R.drawable.ic_dock),
    NavBar("Navigation bar", R.drawable.ic_navbar),
    Controls("Controls", R.drawable.ic_sliders),
    Themes("Themes", R.drawable.ic_palette),
}

/** The app's own settings, stored on this phone, and the ways to the PC list and About. */
@Composable
fun SettingsScreen(
    userName: String,
    onUserNameChange: (String) -> Unit,
    allowVolumeBoost: Boolean,
    onAllowVolumeBoostChange: (Boolean) -> Unit,
    closeObsAfterRecording: Boolean,
    onCloseObsAfterRecordingChange: (Boolean) -> Unit,
    dock: DockSettings,
    onDockChange: ((DockSettings) -> DockSettings) -> Unit,
    /** Built-in and imported themes, and where imports are checked and saved. */
    themes: ThemeStore,
    themeId: String,
    onThemeChange: (String) -> Unit,
    /** The connected PC's GPUs, to choose the one Home shows; empty when not connected. */
    gpus: List<GpuInfo>,
    pcsSummary: String,
    onPcs: () -> Unit,
    onAbout: () -> Unit,
    onCommands: () -> Unit,
    /** Opens the PC check, which says why a feature is missing. */
    onCheck: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    val themeList by themes.themes.collectAsStateWithLifecycle()
    val pageContent = @Composable { shown: Page, wide: Boolean ->
        when (shown) {
            Page.Dock -> DockPage(dock, onDockChange, gpus, wide)
            Page.NavBar -> NavBarPage(dock, onDockChange)
            Page.Controls -> ControlsPage(
                allowVolumeBoost,
                onAllowVolumeBoostChange,
                closeObsAfterRecording,
                onCloseObsAfterRecordingChange,
            )
            Page.Themes -> ThemesPage(themes, themeList, themeId, onThemeChange, wide)
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = isWide(maxWidth, maxHeight)
        BackHandler(enabled = !wide && page != null) { page = null }
        val open = page
        when {
            wide -> {
                val shown = open ?: Page.Dock
                Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        Modifier.width(244.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ScreenHeader("Settings", "Saved on this phone", leading = { BackButton(onBack) })
                        NameCard(userName, onUserNameChange)
                        Column {
                            for (each in Page.entries) {
                                CategoryRow(each.icon, each.title, selected = each == shown) { page = each }
                            }
                            CategoryRow(R.drawable.ic_laptop, "Your PCs", selected = false, chevron = true, onClick = onPcs)
                            CategoryRow(R.drawable.ic_checklist, "PC check", selected = false, chevron = true, onClick = onCheck)
                            CategoryRow(R.drawable.ic_info, "About YaeDock", selected = false, chevron = true, onClick = onAbout)
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(PageShape).background(YaeColors.card)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        Text(shown.title, Modifier.padding(bottom = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        pageContent(shown, true)
                    }
                }
            }
            open != null -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenHeader(open.title, "Saved on this phone", leading = { BackButton({ page = null }) })
                Column(Modifier.fillMaxWidth().clip(PageShape).background(YaeColors.card).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    pageContent(open, false)
                }
            }
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ScreenHeader("Settings", "Saved on this phone", leading = { BackButton(onBack) })
                NameCard(userName, onUserNameChange)
                LinkGroup {
                    LinkRow(Page.Dock.icon, Page.Dock.title, dockSummary(dock)) { page = Page.Dock }
                    LinkRow(Page.NavBar.icon, Page.NavBar.title, navBarSummary(dock.navBar)) { page = Page.NavBar }
                    LinkRow(Page.Controls.icon, Page.Controls.title, controlsSummary(allowVolumeBoost, closeObsAfterRecording)) {
                        page = Page.Controls
                    }
                    val theme = themeList.find { it.id == themeId } ?: themeList.first()
                    LinkRow(Page.Themes.icon, Page.Themes.title, theme.name) { page = Page.Themes }
                }
                LinkGroup {
                    LinkRow(R.drawable.ic_laptop, "Your PCs", pcsSummary, onPcs)
                    LinkRow(R.drawable.ic_checklist, "PC check", "Why a feature is missing, and how to fix it", onCheck)
                    LinkRow(R.drawable.ic_info, "About YaeDock", "Version ${appVersion()} · open source", onAbout)
                    LinkRow(R.drawable.ic_terminal, "PC commands", "Everything yaedock can do in a terminal", onCommands)
                }
            }
        }
    }
}

private val PageShape = RoundedCornerShape(22.dp)

private fun dockSummary(dock: DockSettings): String {
    val keepOn = KEEP_ON_OPTIONS.first { it.first == dock.keepScreenOn }.second
    return if (dock.dim) "$keepOn · dim after ${dock.dimAfterMinutes} min" else "$keepOn · no dimming"
}

private fun navBarSummary(mode: NavBarMode) = when (mode) {
    NavBarMode.ALWAYS -> "Always showing"
    NavBarMode.PORTRAIT -> "Only in portrait"
    NavBarMode.LANDSCAPE -> "Only in landscape"
    NavBarMode.ON_TAP -> "Only when tapped"
}

private fun controlsSummary(allowVolumeBoost: Boolean, closeObs: Boolean) = listOfNotNull(
    "Volume to 150%".takeIf { allowVolumeBoost },
    "OBS closes after recording".takeIf { closeObs },
).joinToString(" · ").ifEmpty { "Volume, OBS" }

/** The user's name for the greeting, with their initial beside it. */
@Composable
private fun NameCard(name: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(YaeColors.card).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "Y" },
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = YaeColors.accent,
            )
        }
        Column(Modifier.weight(1f)) {
            Muted("Your name", size = 12.sp)
            BasicTextField(
                name,
                { onChange(it.take(MAX_NAME_LENGTH)) },
                Modifier.fillMaxWidth().semantics { contentDescription = "Your name" },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Grotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = YaeColors.text),
                cursorBrush = SolidColor(YaeColors.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                decorationBox = { field ->
                    Box {
                        if (name.isEmpty()) Muted("For the greeting", size = 16.sp)
                        field()
                    }
                },
            )
        }
    }
}

/** A landscape list row: the shown page in accent, or a way out of Settings with a [chevron]. */
@Composable
private fun CategoryRow(icon: Int, title: String, selected: Boolean, chevron: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) YaeColors.accent else YaeColors.text
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(ButtonShape)
            .background(if (selected) YaeColors.accentSoft else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = color)
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (chevron) Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = YaeColors.muted)
    }
}

@Composable
private fun DockPage(dock: DockSettings, onDockChange: ((DockSettings) -> DockSettings) -> Unit, gpus: List<GpuInfo>, wide: Boolean) {
    val keepOn = @Composable { modifier: Modifier ->
        Segmented(KEEP_ON_OPTIONS, dock.keepScreenOn, "Keep the screen on", modifier, fontSize = if (wide) 12.sp else 13.sp, fill = !wide) { option ->
            onDockChange { it.copy(keepScreenOn = option) }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingText("Keep the screen on", "While the dashboard is open", Modifier.weight(1f))
            keepOn(Modifier)
        }
    } else {
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingText("Keep the screen on", "While the dashboard is open")
            keepOn(Modifier.fillMaxWidth())
        }
    }
    Divider()
    SwitchRow("Full screen in landscape", "Hides the status and navigation bars", dock.fullScreen) { on ->
        onDockChange { it.copy(fullScreen = on) }
    }
    Divider()
    SwitchRow("Dim when idle", "Darkens after a while without a touch; tap to wake", dock.dim) { on ->
        onDockChange { it.copy(dim = on) }
    }
    if (dock.dim) {
        Segmented(
            DIM_DELAYS.map { it to "$it min" },
            dock.dimAfterMinutes,
            "Dim after",
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            fontSize = 12.sp,
        ) { minutes -> onDockChange { it.copy(dimAfterMinutes = minutes) } }
    }
    Divider()
    SwitchRow("Burn-in protection", "Shifts the layout now and then (OLED)", dock.burnInProtection) { on ->
        onDockChange { it.copy(burnInProtection = on) }
    }
    // Only a PC with more than one GPU has a choice to make.
    if (gpus.size > 1) {
        Divider()
        Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingText("GPU on Home", "Active follows the busiest GPU that is awake")
            Segmented(
                listOf("" to "Active") + gpus.map { it.id to shortGpuName(it.name) },
                dock.homeGpu,
                "GPU on Home",
                Modifier.fillMaxWidth(),
            ) { id -> onDockChange { it.copy(homeGpu = id) } }
        }
    }
}

@Composable
private fun NavBarPage(dock: DockSettings, onDockChange: ((DockSettings) -> DockSettings) -> Unit) {
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingText("Show navigation bar", "Otherwise tap the screen to bring it up; tap again or wait to hide it")
        Segmented(NAV_BAR_OPTIONS, dock.navBar, "Show navigation bar", Modifier.fillMaxWidth()) { option ->
            onDockChange { it.copy(navBar = option) }
        }
    }
}

@Composable
private fun ControlsPage(
    allowVolumeBoost: Boolean,
    onAllowVolumeBoostChange: (Boolean) -> Unit,
    closeObsAfterRecording: Boolean,
    onCloseObsAfterRecordingChange: (Boolean) -> Unit,
) {
    SwitchRow("Volume above 100%", "Up to 150%; the sound can distort", allowVolumeBoost, onAllowVolumeBoostChange)
    Divider()
    SwitchRow(
        "Close OBS after recording",
        "Unless it is streaming or using its virtual camera",
        closeObsAfterRecording,
        onCloseObsAfterRecordingChange,
    )
}

/**
 * Every theme as a small dashboard in its own colours; tapping one switches to
 * it at once. A theme file can be imported: it is checked first, and only saved
 * once the user has seen what the check found.
 */
@Composable
private fun ThemesPage(store: ThemeStore, themes: List<YaeTheme>, themeId: String, onThemeChange: (String) -> Unit, wide: Boolean) {
    val chosen = themes.find { it.id == themeId }?.id ?: themes.first().id
    val perRow = if (wide) 3 else 2
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf<ThemeCheck?>(null) }
    var deleting by remember { mutableStateOf<YaeTheme?>(null) }
    // Many file managers call a .json file "application/octet-stream", so any file can be picked.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            checked = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // One byte past the limit is enough to know the file is too big.
                        val bytes = input.readNBytesCompat(ThemeStore.MAX_SIZE + 1)
                        store.check(bytes.decodeToString())
                    } ?: ThemeCheck.Invalid("The file could not be opened")
                }.getOrElse { ThemeCheck.Invalid("The file could not be read") }
            }
        }
    }

    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingText("Colours", "For every screen; the night dock stays black")
        for (row in themes.chunked(perRow)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (theme in row) {
                    ThemeCard(
                        theme,
                        selected = theme.id == chosen,
                        modifier = Modifier.weight(1f),
                        onDelete = if (theme.custom) ({ deleting = theme }) else null,
                    ) { onThemeChange(theme.id) }
                }
                // Keeps a short last row the same width as the others.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Divider()
        AppIconPicker()
        Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { pick.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingText("Import a theme", "A .json file made from themes/example.json in the YaeDock repository", Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(YaeColors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp), tint = YaeColors.accent)
            }
        }
    }

    when (val result = checked) {
        is ThemeCheck.Invalid -> AlertDialog(
            onDismissRequest = { checked = null },
            containerColor = YaeColors.card,
            title = { Text("Not a theme YaeDock can use") },
            text = { Text(result.reason, color = YaeColors.muted) },
            confirmButton = { TextButton(onClick = { checked = null }) { Text("OK") } },
        )
        is ThemeCheck.Valid -> AlertDialog(
            onDismissRequest = { checked = null },
            containerColor = YaeColors.card,
            title = { Text("Import ${result.theme.name}?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemePreview(result.theme.palette)
                    if (result.replaces) Text("This replaces the imported theme with the id \"${result.theme.id}\".", color = YaeColors.muted, fontSize = 14.sp)
                    if (result.warnings.isEmpty()) {
                        Text("Every colour checks out.", color = YaeColors.muted, fontSize = 14.sp)
                    } else {
                        Text("Some things may be hard to read:", color = YaeColors.amberText, fontSize = 14.sp)
                        for (warning in result.warnings) Muted("· $warning", size = 13.sp, maxLines = 3)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.save(result)
                    onThemeChange(result.theme.id)
                    checked = null
                }) { Text(if (result.warnings.isEmpty()) "Import" else "Import anyway") }
            },
            dismissButton = { TextButton(onClick = { checked = null }) { Text("Cancel") } },
        )
        null -> {}
    }

    deleting?.let { theme ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = YaeColors.card,
            title = { Text("Delete ${theme.name}?") },
            text = { Text("You can import its file again later.", color = YaeColors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    if (theme.id == chosen) onThemeChange(ThemeCatalog.DEFAULT_ID)
                    store.delete(theme.id)
                    deleting = null
                }) { Text("Delete", color = YaeColors.redText) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The launcher icon, chosen apart from the theme: each built-in theme has one.
 * Changing it can take the launcher a moment, and some launchers move the icon
 * off the home screen, so it only changes when picked here.
 */
@Composable
private fun AppIconPicker() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcon.current(context)) }
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingText("App icon", "Separate from the theme. Your launcher may take a moment to update, and may move the icon off the home screen")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (icon in AppIcon.entries) {
                val selected = icon == current
                Column(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .selectable(selected, role = Role.RadioButton) {
                            if (!selected) {
                                AppIcon.set(context, icon)
                                current = icon
                            }
                        }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier.size(56.dp)
                            .border(2.dp, if (selected) YaeColors.accent else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(colorResource(icon.background)),
                    ) {
                        // The foreground is drawn on the 108dp adaptive grid, of which a launcher shows the middle 72.
                        Image(
                            painterResource(icon.foreground),
                            null,
                            Modifier.requiredSize(72.dp).align(Alignment.Center),
                        )
                    }
                    Text(
                        icon.label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) YaeColors.accent else YaeColors.muted,
                    )
                }
            }
        }
    }
}

/** InputStream.readNBytes is Android 13+; this reads up to [limit] bytes on any version. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

@Composable
private fun ThemeCard(theme: YaeTheme, selected: Boolean, modifier: Modifier, onDelete: (() -> Unit)?, onClick: () -> Unit) {
    Column(
        modifier
            .clip(ThemeShape)
            .background(YaeColors.raised)
            .border(if (selected) 2.dp else 1.dp, if (selected) YaeColors.accent else YaeColors.track, ThemeShape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ThemePreview(theme.palette)
        Row(Modifier.padding(start = 4.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(theme.name, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(Icons.Rounded.Check, null, Modifier.padding(end = 4.dp).size(18.dp), tint = YaeColors.accent)
            if (onDelete != null) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Delete ${theme.name}", onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_trash), "Delete ${theme.name}", Modifier.size(16.dp), tint = YaeColors.muted)
                }
            }
        }
        Muted(
            theme.description.ifBlank { if (theme.custom) "Imported" else "" },
            Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp),
            size = 12.sp,
            maxLines = 2,
        )
    }
}

/** A tiny dashboard in [p]'s colours: a gauge with two lines of text, two buttons and a bar. */
@Composable
private fun ThemePreview(p: YaePalette) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.background).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(p.card).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Canvas(Modifier.size(26.dp)) {
                val width = 3.5.dp.toPx()
                val inset = width / 2
                val arc = Size(size.width - width, size.height - width)
                drawArc(p.track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(width))
                drawArc(p.accent, -90f, 230f, false, Offset(inset, inset), arc, style = Stroke(width, cap = StrokeCap.Round))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(width = 40.dp, height = 6.dp).clip(CircleShape).background(p.text))
                Box(Modifier.size(width = 26.dp, height = 5.dp).clip(CircleShape).background(p.muted))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(6.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(p.accent))
            }
            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(6.dp)).background(p.card), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(p.red))
            }
        }
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(p.track)) {
            Box(Modifier.fillMaxWidth(0.62f).fillMaxHeight().clip(CircleShape).background(p.amber))
        }
    }
}

private val ThemeShape = RoundedCornerShape(16.dp)

/** Options in a pill, the chosen one in accent. With [fill] they share the width evenly. */
@Composable
private fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    label: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.sp,
    fill: Boolean = true,
    height: Dp = 38.dp,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier.clip(CircleShape).background(YaeColors.raised).padding(3.dp).semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((option, text) in options) {
            val chosen = option == selected
            Box(
                // Longer labels get more room, so "Landscape" fits beside "On tap".
                (if (fill) Modifier.weight(text.length + 3f) else Modifier).height(height).clip(CircleShape)
                    .background(if (chosen) YaeColors.accentSoft else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option) }
                    .semantics { this.selected = chosen }
                    .padding(horizontal = if (fill) 4.dp else 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    fontSize = fontSize,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (chosen) YaeColors.accent else YaeColors.text,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(YaeColors.track))
}

@Composable
private fun SettingText(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, fontSize = 15.sp)
        Muted(description, Modifier.padding(top = 2.dp), size = 12.sp, maxLines = 3)
    }
}

/** A setting with a description and a switch; tapping anywhere on the row flips it. */
@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingText(title, description, Modifier.weight(1f))
        DockSwitch(checked, onChange, title)
    }
}

@Composable
private fun LinkGroup(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(PageShape).background(YaeColors.card).padding(6.dp)) { content() }
}

/** A row that opens another screen: an icon, a title over a line of detail, a chevron. */
@Composable
private fun LinkRow(icon: Int, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(ButtonShape).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(YaeColors.raised), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = YaeColors.accent)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Muted(detail, size = 12.sp)
        }
        Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(16.dp), tint = YaeColors.muted)
    }
}
