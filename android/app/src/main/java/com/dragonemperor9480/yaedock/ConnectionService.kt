package com.dragonemperor9480.yaedock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the connection to a PC alive while the app is in the background or the
 * screen is off, and shows its status in a notification. It runs while
 * [ConnectionManager] has an active connection and stops itself afterwards.
 */
class ConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connections get() = (application as YaeDockApp).connections
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var media: PcMediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Connection to PC", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while YaeDock is connected to a PC"
                setShowBadge(false)
            },
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(Status.Connecting),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        watchStatus()
        watchWifi()
        watchMedia()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) connections.disconnect()
        media?.handle(intent?.action)
        // After the process is killed the user reconnects from the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        scope.cancel()
        media?.release()
        super.onDestroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchStatus() {
        // Until the PC is reached, look for it on the network in case its
        // address changed.
        var discovering: AutoCloseable? = null
        scope.launch {
            connections.active
                .flatMapLatest { connection -> connection?.status ?: flowOf(null) }
                .map { status -> status?.takeIf { it !is Status.Stopped } }
                .collect { status ->
                    if (status is Status.Connecting || status is Status.Waiting) {
                        if (discovering == null) discovering = connections.discover()
                    } else {
                        discovering?.close()
                        discovering = null
                    }
                    if (status == null) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                    }
                }
        }
    }

    /** Mirrors what plays on the PC into the phone's media controls while the setting is on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchMedia() {
        val session = PcMediaSession(this).also { media = it }
        val settings = (application as YaeDockApp).settings
        scope.launch {
            combine(settings.mediaNotification, connections.active) { on, connection -> connection.takeIf { on } }
                .flatMapLatest { connection ->
                    if (connection == null) {
                        flowOf(null)
                    } else {
                        combine(connection.status, connection.state, connection.art) { status, state, art ->
                            Triple(connection, state?.media.takeIf { status is Status.Connected }, art)
                        }
                    }
                }
                .collect { shown ->
                    val (connection, playing, art) = shown ?: Triple(null, null, null)
                    session.show(playing, art) { connection?.send(it) }
                }
        }
    }

    /**
     * Reconnects as soon as Wi-Fi is back instead of waiting out the backoff,
     * and drops the link at once when Wi-Fi goes, rather than after 30 s of silence.
     */
    private fun watchWifi() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connections.active.value?.let { if (it.status.value !is Status.Connected) it.retryNow() }
            }

            override fun onLost(network: Network) {
                connections.active.value?.dropLink()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun notification(status: Status) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(
            when (status) {
                is Status.Connected -> "Connected to ${status.welcome.hostname}"
                Status.Connecting -> "Connecting…"
                is Status.Waiting -> "Reconnecting…"
                is Status.Rejected -> "The PC refused this phone"
                Status.Stopped -> "Not connected"
            },
        )
        .setContentText(
            when (status) {
                is Status.Waiting -> status.reason
                is Status.Rejected -> "Pair this phone again"
                else -> null
            },
        )
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, DockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0, "Disconnect",
            PendingIntent.getService(
                this, 1,
                Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val CHANNEL = "connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.dragonemperor9480.yaedock.DISCONNECT"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}
