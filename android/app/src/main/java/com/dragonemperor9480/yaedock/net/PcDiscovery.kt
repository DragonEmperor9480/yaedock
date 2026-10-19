package com.dragonemperor9480.yaedock.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** A YaeDock daemon announced on the local network. */
data class DiscoveredPc(val name: String, val host: String, val port: Int, val fingerprint: String)

/**
 * Finds daemons announcing `_yaedock._tcp` over mDNS and reports each one,
 * with the certificate fingerprint from its TXT record, to [onFound].
 *
 * Android's system service does the multicast work, so no extra permission or
 * multicast lock is needed. Networks that block multicast (guest Wi-Fi, client
 * isolation) simply find nothing.
 */
class PcDiscovery(context: Context, private val onFound: (DiscoveredPc) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var listener: NsdManager.DiscoveryListener? = null

    /** Android 14+ resolution callbacks still waiting for a usable answer. */
    private val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()

    /** Before Android 14 only one service can be resolved at a time, so they queue. */
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolve(service)
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start: $errorCode")
                synchronized(this@PcDiscovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        listener = l
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        pending.clear()
        runCatching { nsd.stopServiceDiscovery(l) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        callbacks.clear()
    }

    private fun resolve(service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    // The first update can arrive before the address is known;
                    // keep listening until one has everything.
                    if (report(info, info.hostAddresses)) {
                        synchronized(this@PcDiscovery) { callbacks.remove(this) }
                        runCatching { nsd.unregisterServiceInfoCallback(this) }
                    }
                }
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    synchronized(this@PcDiscovery) { callbacks.remove(this) }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            synchronized(this) {
                if (listener == null) return
                callbacks.add(callback)
            }
            nsd.registerServiceInfoCallback(service, executor, callback)
        } else {
            synchronized(this) {
                pending.addLast(service)
                if (!resolving) resolveNext()
            }
        }
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun resolveNext() {
        val service = pending.removeFirstOrNull()
        resolving = service != null
        if (service == null) return
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                report(info, listOfNotNull(info.host))
                resolveNext()
            }
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = resolveNext()
        })
    }

    /** Reports a resolved PC; returns false if the answer is not complete yet. */
    private fun report(info: NsdServiceInfo, addresses: List<InetAddress>): Boolean {
        val fingerprint = info.attributes["fp"]?.let { String(it, Charsets.US_ASCII) } ?: return false
        // Prefer IPv4: it is what the daemon's pairing QR code uses, and link-local
        // IPv6 addresses need a scope the WebSocket URL cannot carry.
        val host = addresses.firstOrNull { it is Inet4Address }?.hostAddress ?: return false
        onFound(DiscoveredPc(info.serviceName, host, info.port, normalizeFingerprint(fingerprint)))
        return true
    }

    private companion object {
        const val SERVICE_TYPE = "_yaedock._tcp"
        const val TAG = "PcDiscovery"
    }
}
