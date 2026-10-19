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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.ui.DashboardScreen
import com.dragonemperor9480.yaedock.ui.DevicesScreen
import com.dragonemperor9480.yaedock.ui.DockMode
import com.dragonemperor9480.yaedock.ui.ManualPairScreen
import com.dragonemperor9480.yaedock.ui.ScanScreen
import com.dragonemperor9480.yaedock.ui.SettingsScreen

private enum class Screen { Devices, Scan, Manual, Dashboard, Settings }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YaeDockApp
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
    val allowVolumeBoost by app.settings.allowVolumeBoost.collectAsStateWithLifecycle()
    val dock by app.settings.dock.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(if (active != null) Screen.Dashboard else Screen.Devices) }
    // Where the Settings screen returns to.
    var settingsFrom by rememberSaveable { mutableStateOf(Screen.Devices) }
    val openSettings = { settingsFrom = screen; screen = Screen.Settings }

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
            Screen.Dashboard -> { connections.disconnect(); screen = Screen.Devices }
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
            modifier = modifier,
        )
        Screen.Manual -> ManualPairScreen(
            onPair = { connections.pair(it); askForNotifications(); screen = Screen.Dashboard },
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
                DockMode(window, dock) {
                    DashboardScreen(
                        pcName = pcName,
                        status = status,
                        stats = stats.takeIf { status is DaemonConnection.Status.Connected },
                        state = state,
                        history = history,
                        allowVolumeBoost = allowVolumeBoost,
                        send = { connection.send(it) },
                        onRetry = connection::retryNow,
                        onPairAgain = { connections.disconnect(); screen = Screen.Scan },
                        onBack = { connections.disconnect(); screen = Screen.Devices },
                        onSettings = openSettings,
                        modifier = modifier,
                    )
                }
            }
        }
        Screen.Settings -> SettingsScreen(
            allowVolumeBoost = allowVolumeBoost,
            onAllowVolumeBoostChange = app.settings::setAllowVolumeBoost,
            dock = dock,
            onDockChange = app.settings::updateDock,
            onBack = { screen = settingsFrom },
            modifier = modifier,
        )
    }
}

@Composable
private fun YaeDockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
