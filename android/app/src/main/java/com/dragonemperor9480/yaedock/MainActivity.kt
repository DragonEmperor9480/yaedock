package com.dragonemperor9480.yaedock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.ui.AboutScreen
import com.dragonemperor9480.yaedock.ui.CommandsScreen
import com.dragonemperor9480.yaedock.ui.ConnectedShell
import com.dragonemperor9480.yaedock.ui.ControlsScreen
import com.dragonemperor9480.yaedock.ui.DockScreen
import com.dragonemperor9480.yaedock.ui.DevicesScreen
import com.dragonemperor9480.yaedock.ui.DockMode
import com.dragonemperor9480.yaedock.ui.ManualPairScreen
import com.dragonemperor9480.yaedock.ui.NightScreen
import com.dragonemperor9480.yaedock.ui.OverviewScreen
import com.dragonemperor9480.yaedock.ui.ScanScreen
import com.dragonemperor9480.yaedock.ui.SettingsScreen
import com.dragonemperor9480.yaedock.ui.Tab
import com.dragonemperor9480.yaedock.ui.rememberRecordControl
import com.dragonemperor9480.yaedock.ui.YaeDockTheme
import kotlinx.coroutines.launch

private enum class Screen { Devices, Scan, Manual, Dashboard, Settings, About, Commands }

/** The tabs of a connected PC. */
private val TABS = Tab.entries

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YaeDockApp
        // Only on a fresh start: a rotation must not undo leaving a PC.
        if (savedInstanceState == null) app.connections.resumeLast()
        setContent {
            YaeDockTheme {
                Scaffold(Modifier.fillMaxSize()) { padding ->
                    App(app, window, Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun App(app: YaeDockApp, window: Window, modifier: Modifier) {
    val connections = app.connections
    val pcs by app.store.pcs.collectAsStateWithLifecycle()
    val active by connections.active.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Answers the PC sends to commands: a saved screenshot, or what went wrong.
    LaunchedEffect(active) {
        val connection = active ?: return@LaunchedEffect
        launch {
            connection.recordings.collect {
                Toast.makeText(context, "Recording saved: ${it.path.substringAfterLast('/')}", Toast.LENGTH_LONG).show()
            }
        }
        launch {
            connection.screenshots.collect {
                Toast.makeText(context, "Screenshot saved: ${it.path.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
            }
        }
        connection.errors.collect { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
    }
    val allowVolumeBoost by app.settings.allowVolumeBoost.collectAsStateWithLifecycle()
    val closeObsAfterRecording by app.settings.closeObsAfterRecording.collectAsStateWithLifecycle()
    val userName by app.settings.userName.collectAsStateWithLifecycle()
    val dock by app.settings.dock.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(if (active != null) Screen.Dashboard else Screen.Devices) }
    var tab by rememberSaveable { mutableStateOf(Tab.Dock) }
    // Where the Settings screen returns to.
    var settingsFrom by rememberSaveable { mutableStateOf(Screen.Devices) }
    val openSettings = { settingsFrom = screen; screen = Screen.Settings }
    // Commands opens from Settings or About, and returns there.
    var commandsFrom by rememberSaveable { mutableStateOf(Screen.Settings) }
    val openCommands = { commandsFrom = screen; screen = Screen.Commands }

    // The connection's status notification needs this on Android 13+. Without
    // it the connection still runs; only the notification is hidden.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val askForNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    BackHandler(enabled = screen != Screen.Devices) {
        when (screen) {
            Screen.Settings -> screen = settingsFrom
            Screen.About -> screen = Screen.Settings
            Screen.Commands -> screen = commandsFrom
            Screen.Dashboard -> if (tab != Tab.Dock) {
                tab = Tab.Dock
            } else {
                connections.disconnect()
                screen = Screen.Devices
            }
            else -> screen = Screen.Devices
        }
    }

    when (screen) {
        Screen.Devices -> {
            // Keep paired PCs' addresses current while the list is open.
            DisposableEffect(Unit) {
                val discovering = connections.discover()
                onDispose { discovering.close() }
            }
            DevicesScreen(
                pcs = pcs,
                onConnect = { connections.connect(it); askForNotifications(); screen = Screen.Dashboard },
                onForget = { pc -> app.store.remove(pc.fingerprint) },
                onScan = { screen = Screen.Scan },
                onEnterManually = { screen = Screen.Manual },
                onSettings = openSettings,
                modifier = modifier,
            )
        }
        Screen.Scan -> ScanScreen(
            onFound = { connections.pair(it); askForNotifications(); screen = Screen.Dashboard },
            onEnterManually = { screen = Screen.Manual },
            onBack = { screen = Screen.Devices },
            modifier = modifier,
        )
        Screen.Manual -> ManualPairScreen(
            onPair = { connections.pair(it); askForNotifications(); screen = Screen.Dashboard },
            onBack = { screen = Screen.Devices },
            modifier = modifier,
        )
        Screen.Dashboard -> when (val connection = active) {
            null -> LaunchedEffect(Unit) { screen = Screen.Devices }
            else -> {
                val status by connection.status.collectAsStateWithLifecycle()
                val stats by connection.stats.collectAsStateWithLifecycle()
                val state by connection.state.collectAsStateWithLifecycle()
                val history by connections.history.history.collectAsState()
                val pcName by connections.activeName.collectAsStateWithLifecycle()
                val connected = status is DaemonConnection.Status.Connected
                val send: (ClientMessage) -> Unit = { connection.send(it) }
                val welcome = (status as? DaemonConnection.Status.Connected)?.welcome
                val record = rememberRecordControl(welcome, state?.recording, closeObsAfterRecording, send)
                val allPcs = { connections.disconnect(); screen = Screen.Devices }
                val pairAgain = { connections.disconnect(); screen = Screen.Scan }
                DockMode(window, dock) {
                    if (tab == Tab.Night) {
                        NightScreen(stats.takeIf { connected }, welcome, state?.media, onWake = { tab = Tab.Dock }, modifier = modifier)
                        return@DockMode
                    }
                    ConnectedShell(tab, TABS, dock.navBar, onTab = { tab = it }, onSettings = openSettings, modifier = modifier) { wide ->
                        when (tab) {
                            Tab.Overview -> OverviewScreen(
                                pcName = pcName,
                                status = status,
                                stats = stats.takeIf { connected },
                                state = state,
                                history = history,
                                wide = wide,
                                record = record,
                                send = send,
                                onRetry = connection::retryNow,
                                onPairAgain = pairAgain,
                            )
                            Tab.Controls -> ControlsScreen(
                                pcName = pcName,
                                status = status,
                                state = state,
                                wide = wide,
                                allowVolumeBoost = allowVolumeBoost,
                                send = send,
                                onRetry = connection::retryNow,
                                onPairAgain = pairAgain,
                                onSettings = openSettings,
                            )
                            else -> DockScreen(
                                pcName = pcName,
                                status = status,
                                stats = stats.takeIf { connected },
                                state = state,
                                wide = wide,
                                allowVolumeBoost = allowVolumeBoost,
                                userName = userName,
                                record = record,
                                send = send,
                                onRetry = connection::retryNow,
                                onPairAgain = pairAgain,
                                onSettings = openSettings,
                                onAllPcs = allPcs,
                                onOverview = { tab = Tab.Overview },
                            )
                        }
                    }
                }
            }
        }
        Screen.Settings -> SettingsScreen(
            userName = userName,
            onUserNameChange = app.settings::setUserName,
            allowVolumeBoost = allowVolumeBoost,
            onAllowVolumeBoostChange = app.settings::setAllowVolumeBoost,
            closeObsAfterRecording = closeObsAfterRecording,
            onCloseObsAfterRecordingChange = app.settings::setCloseObsAfterRecording,
            dock = dock,
            onDockChange = app.settings::updateDock,
            pcsSummary = pcs.joinToString { it.name }.ifEmpty { "None paired yet" },
            onPcs = { connections.disconnect(); screen = Screen.Devices },
            onAbout = { screen = Screen.About },
            onCommands = openCommands,
            onBack = { screen = settingsFrom },
            modifier = modifier,
        )
        Screen.About -> {
            val pcName by connections.activeName.collectAsStateWithLifecycle()
            AboutScreen(
                connectedPc = pcName.takeIf { active != null },
                onCommands = openCommands,
                onBack = { screen = Screen.Settings },
                modifier = modifier,
            )
        }
        Screen.Commands -> CommandsScreen(onBack = { screen = commandsFrom }, modifier = modifier)
    }
}
