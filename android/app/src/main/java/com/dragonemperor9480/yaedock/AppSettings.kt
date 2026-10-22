package com.dragonemperor9480.yaedock

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When the dashboard keeps the phone's screen from turning off. */
enum class KeepScreenOn { WHILE_CHARGING, ALWAYS, NEVER }

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
)

/** User preferences, stored on the phone. */
class AppSettings(context: Context) {
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

    private val _closeObsAfterRecording = MutableStateFlow(prefs.getBoolean(CLOSE_OBS_AFTER_RECORDING, false))

    /** Whether stopping a recording also closes OBS on the PC. */
    val closeObsAfterRecording: StateFlow<Boolean> = _closeObsAfterRecording.asStateFlow()

    fun setCloseObsAfterRecording(close: Boolean) {
        prefs.edit().putBoolean(CLOSE_OBS_AFTER_RECORDING, close).apply()
        _closeObsAfterRecording.value = close
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
            .apply()
        _dock.value = dock
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
        )
    }

    private companion object {
        const val ALLOW_VOLUME_BOOST = "allow_volume_boost"
        const val CLOSE_OBS_AFTER_RECORDING = "close_obs_after_recording"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val FULL_SCREEN = "full_screen"
        const val DIM = "dim"
        const val DIM_AFTER_MINUTES = "dim_after_minutes"
        const val BURN_IN_PROTECTION = "burn_in_protection"
    }
}
