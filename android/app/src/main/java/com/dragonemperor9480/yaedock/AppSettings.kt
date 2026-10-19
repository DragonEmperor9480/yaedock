package com.dragonemperor9480.yaedock

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private companion object {
        const val ALLOW_VOLUME_BOOST = "allow_volume_boost"
    }
}
