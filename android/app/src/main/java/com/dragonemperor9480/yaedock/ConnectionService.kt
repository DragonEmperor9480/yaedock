package com.dragonemperor9480.yaedock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dragonemperor9480.yaedock.net.DaemonConnection.Status
import com.dragonemperor9480.yaedock.net.Via
import com.dragonemperor9480.yaedock.protocol.UsbOff
import com.dragonemperor9480.yaedock.protocol.UsbPlugged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** Whether this phone is lent to the PC as a microphone. */
    private var phoneMic = false
    private var phoneCamera = false
    private var wifiAwake: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var usbState: BroadcastReceiver? = null
    /** Whether the phone is plugged into a computer, not in accessory mode yet. */
    private var pluggedIn = false
    /** Whether this link already told its PC whether it wants the cable. */
    private var toldAboutCable = false
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
        watchUsbState()
        watchMedia()
        watchPhoneMic()
        watchStreams()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) connections.disconnect()
        media?.handle(intent?.action)
        // After the process is killed the user reconnects from the app.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        usbState?.let(::unregisterReceiver)
        scope.cancel()
        wifiAwake?.let { if (it.isHeld) it.release() }
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
                    // Each new link tells its PC once.
                    if (status !is Status.Connected) toldAboutCable = false
                    tellAboutCable()
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

    /**
     * While this phone is the PC's microphone or camera, the service says so:
     * Android lets nothing else record or film in the background.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchPhoneMic() {
        scope.launch {
            connections.active
                .flatMapLatest { connection ->
                    if (connection == null) flowOf(false to false) else combine(connection.phoneMic, connection.phoneCamera, ::Pair)
                }
                .collect { (mic, camera) ->
                    if (mic == phoneMic && camera == phoneCamera) return@collect
                    phoneMic = mic
                    phoneCamera = camera
                    val status = connections.active.value?.status?.value ?: Status.Connecting
                    val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                        (if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
                    // Refused when the app is not on screen; they are only lent from there.
                    runCatching { ServiceCompat.startForeground(this@ConnectionService, NOTIFICATION_ID, notification(status), types) }
                }
        }
    }

    /**
     * Keeps Wi-Fi from dozing while video or sound streams. A phone's Wi-Fi
     * sleeps between packets to save power, and what the PC sends meanwhile
     * waits at the router, by seconds on some. For stats that is nothing; a
     * stream stalls on it, because the PC's answers are what keep it moving.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchStreams() {
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val lock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(mode, "yaedock:stream")
            .apply { setReferenceCounted(false) }
            .also { wifiAwake = it }
        scope.launch {
            connections.active
                .flatMapLatest { connection ->
                    if (connection == null) {
                        flowOf(false)
                    } else {
                        combine(connection.phoneMic, connection.phoneCamera, connection.screen) { mic, camera, screen ->
                            mic || camera || screen != null
                        }
                    }
                }
                .distinctUntilChanged()
                .collect { streaming -> runCatching { if (streaming) lock.acquire() else lock.release() } }
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
                // A link over the cable carries on without Wi-Fi.
                val connection = connections.active.value ?: return
                if ((connection.status.value as? Status.Connected)?.via != Via.CABLE) connection.dropLink()
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    /**
     * Follows whether the phone is plugged into a computer by USB. A PC
     * running YaeDock cannot tell its paired phone from any other phone on its
     * USB bus, so the phone, plugged in and connected over Wi-Fi, says so; that
     * PC then switches it to the cable, and knows it by itself from then on.
     */
    private fun watchUsbState() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Plugged in with data, not just to a charger, and not on the cable yet.
                pluggedIn = intent.getBooleanExtra("connected", false) &&
                    intent.getBooleanExtra("configured", false) &&
                    !intent.getBooleanExtra("accessory", false)
                if (!pluggedIn) toldAboutCable = false
                tellAboutCable()
            }
        }
        scope.launch {
            (application as YaeDockApp).settings.useCable.collect {
                toldAboutCable = false
                tellAboutCable()
            }
        }
        // A system broadcast; Android does not name the action publicly.
        ContextCompat.registerReceiver(this, receiver, IntentFilter(USB_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        usbState = receiver
    }

    /**
     * Tells the PC what the user wants: plugged in over Wi-Fi, to be switched
     * to the cable; with the setting off, never to be switched, and to be let
     * go if it is on the cable now.
     */
    private fun tellAboutCable() {
        val connection = connections.active.value ?: return
        val status = connection.status.value as? Status.Connected ?: return
        if (toldAboutCable || !status.welcome.usbCable) return
        if (!(application as YaeDockApp).settings.useCable.value) {
            toldAboutCable = connection.send(UsbOff)
        } else if (pluggedIn && status.via == Via.WIFI) {
            toldAboutCable = connection.send(UsbPlugged)
        }
    }

    private fun notification(status: Status) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(
            when (status) {
                is Status.Connected -> "Connected to ${status.welcome.hostname}" +
                    if (status.via == Via.CABLE) " by cable" else ""
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
                is Status.Connected -> when {
                    phoneMic && phoneCamera -> "This phone is its camera and microphone"
                    phoneCamera -> "This phone is its camera"
                    phoneMic -> "This phone is its microphone"
                    else -> null
                }
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
        /** UsbManager.ACTION_USB_STATE, which Android keeps hidden. */
        private const val USB_STATE = "android.hardware.usb.action.USB_STATE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java))
        }
    }
}
