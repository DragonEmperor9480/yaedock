package com.dragonemperor9480.yaedock.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import com.dragonemperor9480.yaedock.YaeDockApp
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where installing a new version of the app stands. */
sealed interface AppInstall {
    data object Idle : AppInstall

    /** [percent] is null while the size is unknown. */
    data class Downloading(val percent: Int?) : AppInstall
    data object Verifying : AppInstall

    /** Handed to Android, which asks the user to confirm. */
    data object Installing : AppInstall
    data class Failed(val message: String) : AppInstall
}

/**
 * Downloads a release's APK for this phone, checks it against the release's
 * `SHA256SUMS`, and hands it to Android's package installer. Android itself
 * refuses an APK that is not signed with the same key as the installed app.
 */
class AppInstaller(private val context: Context, private val client: () -> OkHttpClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<AppInstall>(AppInstall.Idle)
    val state: StateFlow<AppInstall> = _state.asStateFlow()

    private val folder = File(context.cacheDir, "updates")

    /** Whether Android lets this app install others; the user allows it once in system settings. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the user lets YaeDock install apps. */
    fun askToInstall() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(release: Release) {
        when (_state.value) {
            is AppInstall.Downloading, AppInstall.Verifying -> return
            else -> {}
        }
        val apk = release.apk(Build.SUPPORTED_ABIS.toList())
        val sums = release.asset(Releases.SUMS)
        if (apk == null || sums == null) {
            _state.value = AppInstall.Failed("Release ${release.version} has no app for this phone")
            return
        }
        _state.value = AppInstall.Downloading(null)
        scope.launch(Dispatchers.IO) {
            val file = File(folder, apk.name)
            try {
                folder.deleteRecursively()
                folder.mkdirs()
                val expected = Releases.checksum(text(sums.url), apk.name)
                    ?: throw IOException("The release's checksums do not list ${apk.name}")
                val actual = download(apk, file)
                _state.value = AppInstall.Verifying
                if (actual != expected) throw IOException("The download does not match the release's checksum")
                commit(file)
                _state.value = AppInstall.Installing
            } catch (e: Exception) {
                file.delete()
                _state.value = AppInstall.Failed(AppUpdates.describe(e))
            }
        }
    }

    /** What Android said about the install; see [InstallReceiver]. */
    fun onStatus(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> AppInstall.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppInstall.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                AppInstall.Failed("Android refused the update: it is not signed like this app")
            PackageInstaller.STATUS_FAILURE_STORAGE -> AppInstall.Failed("Not enough storage for the update")
            else -> AppInstall.Failed(message ?: "The update was not installed")
        }
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) folder.deleteRecursively()
    }

    private fun text(url: String): String =
        client().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            response.body.string()
        }

    /** Saves [asset] to [file] and returns its SHA-256 in lowercase hex. */
    private fun download(asset: Asset, file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        client().newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: asset.size.takeIf { it > 0 }
            var done = 0L
            var shown: Int? = -1
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        val percent = total?.let { (done * 100 / it).toInt().coerceAtMost(100) }
                        if (percent != shown) {
                            shown = percent
                            _state.value = AppInstall.Downloading(percent)
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hands [file] to the package installer; Android answers through [InstallReceiver]. */
    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Once YaeDock installed itself, later updates need no confirmation.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                session.openWrite("yaedock.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
        }
    }
}

/** Receives the package installer's answer: open its confirm screen, or report how it went. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        val app = context.applicationContext as YaeDockApp
        app.updates.installer.onStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
