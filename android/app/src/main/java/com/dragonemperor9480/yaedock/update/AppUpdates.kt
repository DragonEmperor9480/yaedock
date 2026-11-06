package com.dragonemperor9480.yaedock.update

import android.content.Context
import com.dragonemperor9480.yaedock.AppSettings
import com.dragonemperor9480.yaedock.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where a check for updates stands. */
sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data class Failed(val message: String) : UpdateCheck

    /** The newest release, or null when there is none yet. */
    data class Checked(val newest: Release?) : UpdateCheck
}

/**
 * Asks GitHub for the newest release of YaeDock, for the app and the PC. It
 * only does so when asked, or once a day when the user turned on automatic checks.
 */
class AppUpdates(context: Context, private val settings: AppSettings, private val scope: CoroutineScope) {
    /** This app's version, or null if it is not a release version (it always is). */
    val current: Version? = Version.parse(
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
    )

    /** A plain client: GitHub is not one of the paired PCs, so nothing is pinned. */
    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Downloads and installs a new version of the app. */
    val installer = AppInstaller(context.applicationContext, { client }, scope)

    private val _check = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    val check: StateFlow<UpdateCheck> = _check.asStateFlow()

    /** Whether the last check found a release newer than this app; drives the dot on Settings → About. */
    val appUpdateAvailable: StateFlow<Boolean> = settings.newestRelease
        .map { isNewer(it, current) }
        .stateIn(scope, SharingStarted.Eagerly, isNewer(settings.newestRelease.value, current))

    /** Checks now, unless a check is already running. */
    fun checkNow() {
        if (_check.value == UpdateCheck.Checking) return
        _check.value = UpdateCheck.Checking
        scope.launch {
            _check.value = try {
                val newest = withContext(Dispatchers.IO) { fetch() }
                settings.setUpdateChecked(System.currentTimeMillis(), newest?.version?.toString().orEmpty())
                UpdateCheck.Checked(newest)
            } catch (e: Exception) {
                UpdateCheck.Failed(describe(e))
            }
        }
    }

    /** Checks if automatic checks are on and the last one was a day or more ago. */
    fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (settings.autoUpdateCheck.value && now - settings.lastUpdateCheck >= DAY) checkNow()
    }

    private fun fetch(): Release? {
        val request = Request.Builder()
            .url(BuildConfig.UPDATE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            return Releases.newest(response.body.string())
        }
    }

    companion object {
        const val DAY = 24 * 60 * 60 * 1000L

        /** Whether the version [newest] is newer than [than]; false if either is unknown. */
        fun isNewer(newest: String, than: Version?): Boolean {
            val version = Version.parse(newest) ?: return false
            return than != null && version > than
        }

        fun describe(e: Exception): String = when (e) {
            is java.net.UnknownHostException -> "No internet connection"
            is java.net.SocketTimeoutException -> "GitHub did not answer in time"
            is kotlinx.serialization.SerializationException -> "GitHub sent something unexpected"
            else -> e.message ?: "The check failed"
        }
    }
}
