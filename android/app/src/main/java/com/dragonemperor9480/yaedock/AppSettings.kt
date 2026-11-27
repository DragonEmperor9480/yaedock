package com.dragonemperor9480.yaedock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.dragonemperor9480.yaedock.ui.ThemeCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When the dashboard keeps the phone's screen from turning off. */
enum class KeepScreenOn { WHILE_CHARGING, ALWAYS, NEVER }

/**
 * When the portrait bottom bar stays on screen; otherwise it shows while a
 * finger is on the screen. Landscape tabs always show, in the top row.
 * PORTRAIT and LANDSCAPE were for the old landscape rail and now act as ALWAYS
 * and ON_TAP; they and ON_TAP keep their names, as the setting is saved by name.
 */
enum class NavBarMode { ALWAYS, PORTRAIT, LANDSCAPE, ON_TAP }

/** How the dashboard behaves on a phone sitting in a dock. */
data class DockSettings(
    val keepScreenOn: KeepScreenOn = KeepScreenOn.WHILE_CHARGING,
    /** Hide the status and navigation bars on the dashboard in landscape. */
    val fullScreen: Boolean = true,
    /** Dim the dashboard after [dimAfterMinutes] without a touch, while the screen is kept on. */
    val dim: Boolean = true,
    val dimAfterMinutes: Int = 5,
    /** Shift the dashboard a few pixels now and then, so an OLED screen does not burn in. */
    val burnInProtection: Boolean = true,
    val navBar: NavBarMode = NavBarMode.ALWAYS,
    /** The id of the GPU that Home and the night dock show; empty follows the busiest awake one. */
    val homeGpu: String = "",
    /** What the user hid from Home. */
    val homeHidden: Set<HomePart> = emptySet(),
)

/** A part of Home that can be hidden: a card, or one of the live tiles. */
enum class HomePart(val label: String, val description: String, val tile: Boolean = false) {
    STATS("Live stats", "The CPU, RAM, network, battery and drive tiles"),
    CLOCK("Clock", "The time, the date and your greeting"),
    MEDIA("Now playing", "What plays on the PC, with its controls"),
    ACTIONS("Quick actions", "Screenshot, record, the microphone and the overlay"),
    CONTROLS("Sound & brightness", "The volume and brightness sliders, the output and power profile"),
    CPU("CPU", "Its load and temperature", tile = true),
    RAM("RAM", "How much memory is used", tile = true),
    NETWORK("Network", "Download and upload speed", tile = true),
    BATTERY("Battery", "The laptop's charge; the uptime on a PC without a battery", tile = true),
    DRIVES("Drives", "A tile for each drive, with how full it is", tile = true),
    ;

    companion object {
        /** [parts] as text to store: their names, joined by commas. */
        fun encode(parts: Set<HomePart>): String = parts.joinToString(",") { it.name }

        /** What [encode] wrote; names it does not know are left out. */
        fun decode(text: String?): Set<HomePart> =
            text.orEmpty().split(',').mapNotNull { name -> entries.find { it.name == name } }.toSet()
    }
}

/** Where the controller's sticks sit. */
enum class ControllerLayout {
    /** As on an Xbox pad: the left stick above the D-pad, the right stick below the face buttons, inside. */
    XBOX,

    /** Both sticks low and inside, the D-pad and face buttons above them. */
    SYMMETRIC,

    /** Where the user placed each piece, starting from [XBOX]. */
    CUSTOM,
}

/** How big the controller's sticks and buttons are. */
enum class ControllerSize(val scale: Float) { SMALL(0.85f), MEDIUM(1f), LARGE(1.15f) }

/** The phone as a game controller. */
data class ControllerSettings(
    val layout: ControllerLayout = ControllerLayout.XBOX,
    val size: ControllerSize = ControllerSize.MEDIUM,
    /** Vibrate when a game makes the controller rumble. */
    val vibration: Boolean = true,
    /** The user's own arrangement, used by [ControllerLayout.CUSTOM]; pieces left out sit as in Xbox. */
    val custom: Map<ControllerPiece, Placement> = emptyMap(),
)

/** User preferences, stored on the phone. */
class AppSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _allowVolumeBoost = MutableStateFlow(prefs.getBoolean(ALLOW_VOLUME_BOOST, false))

    /**
     * Whether the volume slider goes past 100% to 150%. Above 100% the PC
     * amplifies the sound in software, which can distort it, so this is off by default.
     */
    val allowVolumeBoost: StateFlow<Boolean> = _allowVolumeBoost.asStateFlow()

    fun setAllowVolumeBoost(allow: Boolean) {
        prefs.edit().putBoolean(ALLOW_VOLUME_BOOST, allow).apply()
        _allowVolumeBoost.value = allow
    }

    private val _screenExtend = MutableStateFlow(prefs.getBoolean(SCREEN_EXTEND, false))

    /** Whether the PC's screen is shown as a second display of its own, rather than a mirror. */
    val screenExtend: StateFlow<Boolean> = _screenExtend.asStateFlow()

    fun setScreenExtend(extend: Boolean) {
        prefs.edit().putBoolean(SCREEN_EXTEND, extend).apply()
        _screenExtend.value = extend
    }

    private val _screenHeight = MutableStateFlow(prefs.getInt(SCREEN_HEIGHT, 0))

    /** The most lines the PC's picture has, such as 720; 0 for as many as there are. */
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()

    fun setScreenHeight(height: Int) {
        prefs.edit().putInt(SCREEN_HEIGHT, height).apply()
        _screenHeight.value = height
    }

    private val _cameraHeight = MutableStateFlow(prefs.getInt(CAMERA_HEIGHT, 240))

    /** How many lines the pictures have when this phone is the PC's camera, such as 720. */
    val cameraHeight: StateFlow<Int> = _cameraHeight.asStateFlow()

    fun setCameraHeight(height: Int) {
        prefs.edit().putInt(CAMERA_HEIGHT, height).apply()
        _cameraHeight.value = height
    }

    private val _useCable = MutableStateFlow(prefs.getBoolean(USE_CABLE, true))

    /**
     * Whether the phone talks to a paired PC over the USB cable when plugged
     * into it, and asks a PC it is plugged into to switch it to the cable.
     */
    val useCable: StateFlow<Boolean> = _useCable.asStateFlow()

    init {
        // Also for a setting turned off before the app had this switch.
        handleCable(_useCable.value)
    }

    fun setUseCable(use: Boolean) {
        prefs.edit().putBoolean(USE_CABLE, use).apply()
        _useCable.value = use
        handleCable(use)
    }

    /**
     * Lets Android open the app for a PC that switched the phone to the cable,
     * or, with the cable off, keeps Android from asking about it at all.
     */
    private fun handleCable(use: Boolean) {
        runCatching {
            app.packageManager.setComponentEnabledSetting(
                ComponentName(app, "${app.packageName}.CableHandler"),
                if (use) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }

    private val _closeObsAfterRecording = MutableStateFlow(prefs.getBoolean(CLOSE_OBS_AFTER_RECORDING, false))

    /** Whether stopping a recording also closes OBS on the PC. */
    val closeObsAfterRecording: StateFlow<Boolean> = _closeObsAfterRecording.asStateFlow()

    fun setCloseObsAfterRecording(close: Boolean) {
        prefs.edit().putBoolean(CLOSE_OBS_AFTER_RECORDING, close).apply()
        _closeObsAfterRecording.value = close
    }

    private val _mediaNotification = MutableStateFlow(prefs.getBoolean(MEDIA_NOTIFICATION, false))

    /**
     * Whether what plays on the PC shows in the phone's own media controls (the
     * notification shade and lock screen), with its cover and a seek bar.
     */
    val mediaNotification: StateFlow<Boolean> = _mediaNotification.asStateFlow()

    fun setMediaNotification(show: Boolean) {
        prefs.edit().putBoolean(MEDIA_NOTIFICATION, show).apply()
        _mediaNotification.value = show
    }

    private val _dockClock = MutableStateFlow(prefs.getString(DOCK_CLOCK, null) ?: "classic")

    /** The id of the clock on the dock's clock card. */
    val dockClock: StateFlow<String> = _dockClock.asStateFlow()

    fun setDockClock(id: String) {
        prefs.edit().putString(DOCK_CLOCK, id).apply()
        _dockClock.value = id
    }

    private val _nightClock = MutableStateFlow(prefs.getString(NIGHT_CLOCK, null) ?: "classic")

    /** The id of the clock on the night (always-on) screen. */
    val nightClock: StateFlow<String> = _nightClock.asStateFlow()

    fun setNightClock(id: String) {
        prefs.edit().putString(NIGHT_CLOCK, id).apply()
        _nightClock.value = id
    }

    private val _userName = MutableStateFlow(prefs.getString(USER_NAME, "").orEmpty())

    /** What the greeting calls the user; empty leaves the greeting without a name. */
    val userName: StateFlow<String> = _userName.asStateFlow()

    fun setUserName(name: String) {
        prefs.edit().putString(USER_NAME, name).apply()
        _userName.value = name
    }

    private val _themeId = MutableStateFlow(prefs.getString(THEME, null) ?: ThemeCatalog.DEFAULT_ID)

    /** The chosen theme's id; one that no longer exists falls back to the first theme. */
    val themeId: StateFlow<String> = _themeId.asStateFlow()

    fun setThemeId(id: String) {
        prefs.edit().putString(THEME, id).apply()
        _themeId.value = id
    }

    private val _onboarded = MutableStateFlow(prefs.getBoolean(ONBOARDED, false))

    /** Whether the first-run welcome has been through, so it shows only once. */
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    /** The version whose notes were last shown, or offered, after an update. */
    private val _notesSeen = MutableStateFlow(prefs.getString(NOTES_SEEN, null))

    /**
     * Whether to show what's new in [version] once: after an update, and not
     * after a fresh install, where everything is new.
     */
    fun notesDue(version: String): Boolean = _onboarded.value && _notesSeen.value != version

    fun setNotesSeen(version: String) {
        prefs.edit().putString(NOTES_SEEN, version).apply()
        _notesSeen.value = version
    }

    /** Ends the welcome with the name the user gave. */
    fun finishOnboarding(name: String) {
        prefs.edit().putString(USER_NAME, name).putBoolean(ONBOARDED, true).apply()
        _userName.value = name
        _onboarded.value = true
    }

    private val _autoUpdateCheck = MutableStateFlow(prefs.getBoolean(AUTO_UPDATE_CHECK, false))

    /**
     * Whether the app asks GitHub for a new release when it opens, at most once a
     * day. Off by default: otherwise the app only talks to the PCs on your network.
     */
    val autoUpdateCheck: StateFlow<Boolean> = _autoUpdateCheck.asStateFlow()

    fun setAutoUpdateCheck(check: Boolean) {
        prefs.edit().putBoolean(AUTO_UPDATE_CHECK, check).apply()
        _autoUpdateCheck.value = check
    }

    /** When the app last asked for releases, in milliseconds since the epoch; 0 if never. */
    val lastUpdateCheck: Long get() = prefs.getLong(LAST_UPDATE_CHECK, 0)

    private val _newestRelease = MutableStateFlow(prefs.getString(NEWEST_RELEASE, "").orEmpty())

    /** The newest release version the last check found, so a new one stays marked between launches. */
    val newestRelease: StateFlow<String> = _newestRelease.asStateFlow()

    fun setUpdateChecked(at: Long, newest: String) {
        prefs.edit().putLong(LAST_UPDATE_CHECK, at).putString(NEWEST_RELEASE, newest).apply()
        _newestRelease.value = newest
    }

    private val _dock = MutableStateFlow(loadDock())
    val dock: StateFlow<DockSettings> = _dock.asStateFlow()

    fun updateDock(change: (DockSettings) -> DockSettings) {
        val dock = change(_dock.value)
        prefs.edit()
            .putString(KEEP_SCREEN_ON, dock.keepScreenOn.name)
            .putBoolean(FULL_SCREEN, dock.fullScreen)
            .putBoolean(DIM, dock.dim)
            .putInt(DIM_AFTER_MINUTES, dock.dimAfterMinutes)
            .putBoolean(BURN_IN_PROTECTION, dock.burnInProtection)
            .putString(NAV_BAR, dock.navBar.name)
            .putString(HOME_GPU, dock.homeGpu)
            .putString(HOME_HIDDEN, HomePart.encode(dock.homeHidden))
            .apply()
        _dock.value = dock
    }

    private val _controller = MutableStateFlow(loadController())
    val controller: StateFlow<ControllerSettings> = _controller.asStateFlow()

    fun updateController(change: (ControllerSettings) -> ControllerSettings) {
        val controller = change(_controller.value)
        prefs.edit()
            .putString(CONTROLLER_LAYOUT, controller.layout.name)
            .putString(CONTROLLER_SIZE, controller.size.name)
            .putBoolean(CONTROLLER_VIBRATION, controller.vibration)
            .putString(CONTROLLER_CUSTOM, ControllerPlacements.encode(controller.custom))
            .apply()
        _controller.value = controller
    }

    private fun loadController(): ControllerSettings {
        val defaults = ControllerSettings()
        return ControllerSettings(
            layout = prefs.getString(CONTROLLER_LAYOUT, null)
                ?.let { name -> ControllerLayout.entries.find { it.name == name } }
                ?: defaults.layout,
            size = prefs.getString(CONTROLLER_SIZE, null)
                ?.let { name -> ControllerSize.entries.find { it.name == name } }
                ?: defaults.size,
            vibration = prefs.getBoolean(CONTROLLER_VIBRATION, defaults.vibration),
            custom = ControllerPlacements.decode(prefs.getString(CONTROLLER_CUSTOM, null)),
        )
    }

    private fun loadDock(): DockSettings {
        val defaults = DockSettings()
        return DockSettings(
            keepScreenOn = prefs.getString(KEEP_SCREEN_ON, null)
                ?.let { name -> KeepScreenOn.entries.find { it.name == name } }
                ?: defaults.keepScreenOn,
            fullScreen = prefs.getBoolean(FULL_SCREEN, defaults.fullScreen),
            dim = prefs.getBoolean(DIM, defaults.dim),
            dimAfterMinutes = prefs.getInt(DIM_AFTER_MINUTES, defaults.dimAfterMinutes),
            burnInProtection = prefs.getBoolean(BURN_IN_PROTECTION, defaults.burnInProtection),
            navBar = prefs.getString(NAV_BAR, null)
                ?.let { name -> NavBarMode.entries.find { it.name == name } }
                ?: defaults.navBar,
            homeGpu = prefs.getString(HOME_GPU, null) ?: defaults.homeGpu,
            homeHidden = HomePart.decode(prefs.getString(HOME_HIDDEN, null)),
        )
    }

    private companion object {
        const val ALLOW_VOLUME_BOOST = "allow_volume_boost"
        const val CLOSE_OBS_AFTER_RECORDING = "close_obs_after_recording"
        const val SCREEN_EXTEND = "screen_extend"
        const val SCREEN_HEIGHT = "screen_height"
        const val CAMERA_HEIGHT = "camera_height"
        const val USE_CABLE = "use_cable"
        const val MEDIA_NOTIFICATION = "media_notification"
        const val DOCK_CLOCK = "dock_clock"
        const val NIGHT_CLOCK = "night_clock"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val FULL_SCREEN = "full_screen"
        const val DIM = "dim"
        const val DIM_AFTER_MINUTES = "dim_after_minutes"
        const val BURN_IN_PROTECTION = "burn_in_protection"
        const val NAV_BAR = "nav_bar"
        const val CONTROLLER_LAYOUT = "controller_layout"
        const val CONTROLLER_SIZE = "controller_size"
        const val CONTROLLER_VIBRATION = "controller_vibration"
        const val CONTROLLER_CUSTOM = "controller_custom"
        const val HOME_GPU = "home_gpu"
        const val HOME_HIDDEN = "home_hidden"
        const val USER_NAME = "user_name"
        const val ONBOARDED = "onboarded"
        const val NOTES_SEEN = "notes_seen"
        const val THEME = "theme"
        const val AUTO_UPDATE_CHECK = "auto_update_check"
        const val LAST_UPDATE_CHECK = "last_update_check"
        const val NEWEST_RELEASE = "newest_release"
    }
}
