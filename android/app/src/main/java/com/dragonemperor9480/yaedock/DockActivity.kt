package com.dragonemperor9480.yaedock

import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import androidx.core.content.IntentCompat
import com.dragonemperor9480.yaedock.net.UsbCable
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.net.Via
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.ui.AboutScreen
import com.dragonemperor9480.yaedock.ui.CheckScreen
import com.dragonemperor9480.yaedock.ui.CommandsScreen
import com.dragonemperor9480.yaedock.ui.UpdatesState
import com.dragonemperor9480.yaedock.ui.ConnectedShell
import com.dragonemperor9480.yaedock.ui.ControlsScreen
import com.dragonemperor9480.yaedock.ui.DockScreen
import com.dragonemperor9480.yaedock.ui.DevicesScreen
import com.dragonemperor9480.yaedock.ui.DockMode
import com.dragonemperor9480.yaedock.ui.ManualPairScreen
import com.dragonemperor9480.yaedock.ui.ControllerScreen
import com.dragonemperor9480.yaedock.ui.InputScreen
import com.dragonemperor9480.yaedock.ui.MirrorScreen
import com.dragonemperor9480.yaedock.ui.NightScreen
import com.dragonemperor9480.yaedock.ui.OnboardingScreen
import com.dragonemperor9480.yaedock.ui.OverviewScreen
import com.dragonemperor9480.yaedock.ui.PhoneScreen
import com.dragonemperor9480.yaedock.ui.phoneLent
import com.dragonemperor9480.yaedock.ui.ScanScreen
import com.dragonemperor9480.yaedock.ui.SettingsScreen
import com.dragonemperor9480.yaedock.ui.Tab
import com.dragonemperor9480.yaedock.ui.rememberRecordControl
import com.dragonemperor9480.yaedock.ui.YaeDockTheme
import com.dragonemperor9480.yaedock.ui.ClockChoice
import com.dragonemperor9480.yaedock.ui.LocalClocks
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.launch

private enum class Screen { Onboarding, Devices, Scan, Manual, Dashboard, Mirror, Input, Controller, Settings, About, Commands, Check }

/** The tabs of a connected PC. */
private val TABS = Tab.entries

class DockActivity : ComponentActivity() {
    // Stats are only worth sending while this screen is seen.
    override fun onStart() {
        super.onStart()
        (application as YaeDockApp).connections.setBackground(false)
    }

    override fun onStop() {
        (application as YaeDockApp).connections.setBackground(true)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeCable(intent)
    }

    /** A PC running YaeDock that this phone was just plugged into, which Android opened the app for. */
    private fun takeCable(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            ?.let { UsbCable.attached(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YaeDockApp
        // Plugged into a PC: the cable may be in the intent, or attached before the app started.
        UsbCable.look(this)
        takeCable(intent)
        // Only on a fresh start: a rotation must not undo leaving a PC.
        if (savedInstanceState == null) {
            app.connections.resumeLast()
            // Asks GitHub only if the user turned on automatic checks.
            app.updates.checkIfDue()
        }
        setContent {
            val themeId by app.settings.themeId.collectAsStateWithLifecycle()
            val themes by app.themes.themes.collectAsStateWithLifecycle()
            val palette = (themes.find { it.id == themeId } ?: themes.first()).palette
            // Dark icons on a light theme's status and navigation bars, light ones on a dark theme.
            DisposableEffect(palette.dark) {
                val bars = if (palette.dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            val clocks by app.clocks.clocks.collectAsStateWithLifecycle()
            val dockClock by app.settings.dockClock.collectAsStateWithLifecycle()
            val nightClock by app.settings.nightClock.collectAsStateWithLifecycle()
            val userName by app.settings.userName.collectAsStateWithLifecycle()
            val choice = remember(clocks, dockClock, nightClock, userName) {
                ClockChoice(app.clocks.find(dockClock), app.clocks.find(nightClock), userName)
            }
            YaeDockTheme(palette) {
                CompositionLocalProvider(LocalClocks provides choice) {
                    // Room for the keyboard too, so a focused text field is never hidden behind it.
                    Scaffold(Modifier.fillMaxSize(), contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime)) { padding ->
                        App(app, window, Modifier.padding(padding))
                    }
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
    val mediaNotification by app.settings.mediaNotification.collectAsStateWithLifecycle()
    val useCable by app.settings.useCable.collectAsStateWithLifecycle()
    val userName by app.settings.userName.collectAsStateWithLifecycle()
    val dock by app.settings.dock.collectAsStateWithLifecycle()
    val themeId by app.settings.themeId.collectAsStateWithLifecycle()
    val updateAvailable by app.updates.appUpdateAvailable.collectAsStateWithLifecycle()
    var screen by rememberSaveable {
        mutableStateOf(
            when {
                !app.settings.onboarded.value -> Screen.Onboarding
                active != null -> Screen.Dashboard
                else -> Screen.Devices
            },
        )
    }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    // The night dock covers the tabs; it is left by a tap.
    var night by rememberSaveable { mutableStateOf(false) }
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

    // A link that starts by itself, such as pairing over the USB cable, shows the PC.
    LaunchedEffect(active, screen) {
        if (active != null && screen in setOf(Screen.Devices, Screen.Scan, Screen.Manual)) screen = Screen.Dashboard
    }

    // The welcome handles back itself, between its pages; on the first one, back leaves the app.
    BackHandler(enabled = screen != Screen.Devices && screen != Screen.Onboarding) {
        when (screen) {
            Screen.Settings -> screen = settingsFrom
            Screen.About -> screen = Screen.Settings
            Screen.Commands -> screen = commandsFrom
            Screen.Check -> screen = Screen.Settings
            Screen.Mirror, Screen.Input, Screen.Controller -> screen = Screen.Dashboard
            Screen.Dashboard -> if (night) {
                night = false
            } else if (tab != Tab.Home) {
                tab = Tab.Home
            } else {
                connections.disconnect()
                screen = Screen.Devices
            }
            else -> screen = Screen.Devices
        }
    }

    when (screen) {
        Screen.Onboarding -> OnboardingScreen(
            initialName = userName,
            onFinish = { name -> app.settings.finishOnboarding(name); screen = Screen.Devices },
            modifier = modifier,
        )
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
                val art by connection.art.collectAsStateWithLifecycle()
                val history by connections.history.history.collectAsState()
                val pcName by connections.activeName.collectAsStateWithLifecycle()
                val connected = status is DaemonConnection.Status.Connected
                val send: (ClientMessage) -> Unit = { connection.send(it) }
                val welcome = (status as? DaemonConnection.Status.Connected)?.welcome
                val record = rememberRecordControl(welcome, state?.recording, closeObsAfterRecording, send)
                val allPcs = { connections.disconnect(); screen = Screen.Devices }
                val pairAgain = { connections.disconnect(); screen = Screen.Scan }
                val phoneMic by connection.phoneMic.collectAsStateWithLifecycle()
                val askForMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        connection.setPhoneMic(true)
                    } else {
                        Toast.makeText(context, "YaeDock needs the microphone permission for that", Toast.LENGTH_LONG).show()
                    }
                }
                val phoneCamera by connection.phoneCamera.collectAsStateWithLifecycle()
                val phoneSpeaker by connection.phoneSpeaker.collectAsStateWithLifecycle()
                // The side asked for while the permission question is open.
                var wantedFront by remember { mutableStateOf(false) }
                val cameraHeight by app.settings.cameraHeight.collectAsStateWithLifecycle()
                val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) {
                        connection.lendPhoneCamera(context, wantedFront, app.settings.cameraHeight.value)
                    } else {
                        Toast.makeText(context, "YaeDock needs the camera permission for that", Toast.LENGTH_LONG).show()
                    }
                }
                DockMode(window, dock, night = night) {
                    if (night) {
                        NightScreen(stats.takeIf { connected }, welcome, state?.media, dock.homeGpu, onWake = { night = false }, modifier = modifier)
                        return@DockMode
                    }
                    ConnectedShell(
                        tab,
                        TABS,
                        dock.navBar,
                        onTab = { tab = it },
                        onSettings = openSettings,
                        modifier = modifier,
                        busy = Tab.Phone.takeIf { phoneLent(phoneMic, phoneCamera, phoneSpeaker) },
                    ) { wide ->
                        when (tab) {
                            Tab.Stats -> OverviewScreen(
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
                            Tab.Phone -> PhoneScreen(
                                pcName = pcName,
                                status = status,
                                wide = wide,
                                onRetry = connection::retryNow,
                                onPairAgain = pairAgain,
                                onSettings = openSettings,
                                onScreen = { extend -> app.settings.setScreenExtend(extend); screen = Screen.Mirror },
                                phoneMic = phoneMic,
                                onPhoneMic = { on ->
                                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                        PackageManager.PERMISSION_GRANTED
                                    if (on && !granted) askForMic.launch(Manifest.permission.RECORD_AUDIO) else connection.setPhoneMic(on)
                                },
                                phoneSpeaker = phoneSpeaker,
                                onPhoneSpeaker = connection::setPhoneSpeaker,
                                phoneCamera = phoneCamera,
                                cameraHeight = cameraHeight,
                                onCameraHeight = app.settings::setCameraHeight,
                                onInput = { screen = Screen.Input },
                                onController = { screen = Screen.Controller },
                                onPhoneCamera = { front ->
                                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                        PackageManager.PERMISSION_GRANTED
                                    when {
                                        front == null -> connection.takeBackPhoneCamera()
                                        granted -> connection.lendPhoneCamera(context, front, cameraHeight)
                                        else -> {
                                            wantedFront = front
                                            askForCamera.launch(Manifest.permission.CAMERA)
                                        }
                                    }
                                },
                            )
                            Tab.Home -> DockScreen(
                                pcName = pcName,
                                status = status,
                                stats = stats.takeIf { connected },
                                state = state,
                                art = art,
                                history = history,
                                wide = wide,
                                allowVolumeBoost = allowVolumeBoost,
                                userName = userName,
                                record = record,
                                send = send,
                                onRetry = connection::retryNow,
                                onPairAgain = pairAgain,
                                onSettings = openSettings,
                                onAllPcs = allPcs,
                                onOverview = { tab = Tab.Stats },
                                onNight = { night = true },
                            )
                        }
                    }
                }
            }
        }
        Screen.Mirror -> when (val connection = active) {
            null -> LaunchedEffect(Unit) { screen = Screen.Devices }
            else -> {
                val status by connection.status.collectAsStateWithLifecycle()
                val pcName by connections.activeName.collectAsStateWithLifecycle()
                val extend by app.settings.screenExtend.collectAsStateWithLifecycle()
                val height by app.settings.screenHeight.collectAsStateWithLifecycle()
                val canExtend = (status as? DaemonConnection.Status.Connected)?.welcome?.screenExtend == true
                MirrorScreen(
                    connection = connection,
                    connected = status is DaemonConnection.Status.Connected,
                    pcName = pcName,
                    canExtend = canExtend,
                    extend = extend && canExtend,
                    maxHeight = height,
                    onExtend = app.settings::setScreenExtend,
                    onMaxHeight = app.settings::setScreenHeight,
                    onBack = { screen = Screen.Dashboard },
                    modifier = modifier,
                )
            }
        }
        Screen.Input -> when (val connection = active) {
            null -> LaunchedEffect(Unit) { screen = Screen.Devices }
            else -> {
                val status by connection.status.collectAsStateWithLifecycle()
                val pcName by connections.activeName.collectAsStateWithLifecycle()
                InputScreen(
                    pcName = pcName,
                    connected = status is DaemonConnection.Status.Connected,
                    send = { connection.send(it) },
                    onBack = { screen = Screen.Dashboard },
                    modifier = modifier,
                )
            }
        }
        Screen.Controller -> when (val connection = active) {
            null -> LaunchedEffect(Unit) { screen = Screen.Devices }
            else -> {
                val status by connection.status.collectAsStateWithLifecycle()
                val pcName by connections.activeName.collectAsStateWithLifecycle()
                val controller by app.settings.controller.collectAsStateWithLifecycle()
                val via = (status as? DaemonConnection.Status.Connected)?.via?.let { if (it == Via.CABLE) "cable" else "Wi-Fi" }
                ControllerScreen(
                    pcName = pcName,
                    via = via,
                    settings = controller,
                    onVibration = { on -> app.settings.updateController { it.copy(vibration = on) } },
                    onLayout = { arranged ->
                        app.settings.updateController { it.copy(layout = ControllerLayout.CUSTOM, custom = arranged) }
                    },
                    rumbles = connection.rumble,
                    send = { connection.send(it) },
                    onBack = { screen = Screen.Dashboard },
                    modifier = modifier,
                )
            }
        }
        Screen.Settings -> {
            val activeStatus = active?.status?.collectAsStateWithLifecycle()?.value
            val gpus = (activeStatus as? DaemonConnection.Status.Connected)?.welcome?.gpus.orEmpty()
            SettingsScreen(
                userName = userName,
                onUserNameChange = app.settings::setUserName,
                allowVolumeBoost = allowVolumeBoost,
                onAllowVolumeBoostChange = app.settings::setAllowVolumeBoost,
                closeObsAfterRecording = closeObsAfterRecording,
                onCloseObsAfterRecordingChange = app.settings::setCloseObsAfterRecording,
                mediaNotification = mediaNotification,
                onMediaNotificationChange = app.settings::setMediaNotification,
                useCable = useCable,
                onUseCableChange = app.settings::setUseCable,
                dock = dock,
                onDockChange = app.settings::updateDock,
                controller = app.settings.controller.collectAsStateWithLifecycle().value,
                onControllerChange = app.settings::updateController,
                themes = app.themes,
                themeId = themeId,
                onThemeChange = app.settings::setThemeId,
                clocks = app.clocks,
                dockClockId = app.settings.dockClock.collectAsStateWithLifecycle().value,
                nightClockId = app.settings.nightClock.collectAsStateWithLifecycle().value,
                onDockClockChange = app.settings::setDockClock,
                onNightClockChange = app.settings::setNightClock,
                gpus = gpus,
                pcsSummary = pcs.joinToString { it.name }.ifEmpty { "None paired yet" },
                onPcs = { connections.disconnect(); screen = Screen.Devices },
                onAbout = { screen = Screen.About },
                updateAvailable = updateAvailable,
                onCommands = openCommands,
                onCheck = { screen = Screen.Check },
                onBack = { screen = settingsFrom },
                modifier = modifier,
            )
        }
        Screen.About -> {
            val pcName by connections.activeName.collectAsStateWithLifecycle()
            val activeStatus = active?.status?.collectAsStateWithLifecycle()?.value
            val connectedWelcome = (activeStatus as? DaemonConnection.Status.Connected)?.welcome
            val updateCheck by app.updates.check.collectAsStateWithLifecycle()
            val newestRelease by app.settings.newestRelease.collectAsStateWithLifecycle()
            val autoUpdateCheck by app.settings.autoUpdateCheck.collectAsStateWithLifecycle()
            val appInstall by app.updates.installer.state.collectAsStateWithLifecycle()
            val pcUpdate = active?.pcUpdate?.collectAsStateWithLifecycle()?.value ?: DaemonConnection.PcUpdate.Idle
            AboutScreen(
                connectedPc = pcName.takeIf { connectedWelcome != null },
                pcDetail = connectedWelcome?.let { "${it.os} · port ${active?.target?.port}" },
                updates = UpdatesState(
                    check = updateCheck,
                    app = app.updates.current,
                    pc = connectedWelcome?.daemonVersion,
                    remembered = newestRelease,
                    autoCheck = autoUpdateCheck,
                    onAutoCheckChange = app.settings::setAutoUpdateCheck,
                    onCheck = app.updates::checkNow,
                    install = appInstall,
                    onInstall = { release ->
                        val installer = app.updates.installer
                        // Android asks once whether YaeDock may install apps.
                        if (installer.canInstall()) installer.install(release) else installer.askToInstall()
                    },
                    pcCannotUpdate = connectedWelcome?.cannotUpdate,
                    pcUpdate = pcUpdate,
                    onUpdatePc = { version -> active?.updatePc(version) },
                ),
                onCommands = openCommands,
                onBack = { screen = Screen.Settings },
                modifier = modifier,
            )
        }
        Screen.Commands -> CommandsScreen(onBack = { screen = commandsFrom }, modifier = modifier)
        Screen.Check -> {
            val pcName by connections.activeName.collectAsStateWithLifecycle()
            val activeStatus = active?.status?.collectAsStateWithLifecycle()?.value
            CheckScreen(
                connection = active,
                connected = activeStatus is DaemonConnection.Status.Connected,
                pcName = pcName.ifEmpty { null },
                onBack = { screen = Screen.Settings },
                modifier = modifier,
            )
        }
    }
}
