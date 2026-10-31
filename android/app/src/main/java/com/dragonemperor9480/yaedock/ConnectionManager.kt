package com.dragonemperor9480.yaedock

import android.content.Context
import android.os.Build
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.net.DiscoveredPc
import com.dragonemperor9480.yaedock.net.PcDiscovery
import com.dragonemperor9480.yaedock.net.Target
import com.dragonemperor9480.yaedock.pairing.PairedPc
import com.dragonemperor9480.yaedock.pairing.PairingInfo
import com.dragonemperor9480.yaedock.pairing.PcStore
import com.dragonemperor9480.yaedock.protocol.Hello
import com.dragonemperor9480.yaedock.ui.StatsHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Owns the one active link to a PC, and the stats history it feeds. While a
 * link is active, [ConnectionService] keeps the app running in the background.
 *
 * It also follows paired PCs around the network: when mDNS finds one at a new
 * address, the address is saved, and a link that cannot reach the old address
 * moves to the new one.
 */
class ConnectionManager(
    private val context: Context,
    private val store: PcStore,
    private val scope: CoroutineScope,
) {
    private val _active = MutableStateFlow<DaemonConnection?>(null)
    val active: StateFlow<DaemonConnection?> = _active.asStateFlow()

    /** What to call the active PC: its saved name, or its address while pairing. */
    private val _activeName = MutableStateFlow("")
    val activeName: StateFlow<String> = _activeName.asStateFlow()

    val history = StatsHistory()
    private var historyJob: Job? = null

    private val discovery = PcDiscovery(context, ::onDiscovered)
    private var discoveryUsers = 0
    private var discoveryStop: Job? = null

    private val deviceName: String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { !it.isNullOrBlank() }
        .joinToString(" ")
        .ifEmpty { "Android phone" }

    /** Connects to a PC that is already paired. */
    fun connect(pc: PairedPc) {
        start(Target(pc.host, pc.port, pc.fingerprint), code = null)
        _activeName.value = pc.name
        store.rememberLast(pc.fingerprint)
    }

    /** Connects to the PC used last, for an app that just opened with no link. */
    fun resumeLast() {
        if (_active.value == null) store.last()?.let(::connect)
    }

    /**
     * Pairs with a new PC using the code from its QR code. Once the daemon
     * returns a token the PC is saved, and reconnects use the token.
     */
    fun pair(info: PairingInfo) {
        start(Target(info.host, info.port, info.fingerprint), code = info.code)
        _activeName.value = info.host
    }

    fun disconnect() {
        historyJob?.cancel()
        _active.value?.stop()
        _active.value = null
    }

    /**
     * Looks for paired PCs on the network until the returned handle is closed.
     * Discovery runs while anyone holds a handle, and for a few seconds after,
     * so a handover (the PC list closing as a connection starts) keeps the same
     * search: Android's mDNS client finds nothing if a search is stopped and
     * restarted within moments.
     */
    @Synchronized
    fun discover(): AutoCloseable {
        discoveryStop?.cancel()
        discoveryStop = null
        if (discoveryUsers++ == 0) discovery.start()
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    if (--discoveryUsers == 0) {
                        discoveryStop = scope.launch {
                            delay(DISCOVERY_LINGER_MS)
                            synchronized(this@ConnectionManager) {
                                if (discoveryUsers == 0) discovery.stop()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun onDiscovered(found: DiscoveredPc) {
        val pc = store.find(found.fingerprint) ?: return
        if (pc.host != found.host || pc.port != found.port) {
            store.updateAddress(found.fingerprint, found.host, found.port)
        }
        // Compare with the address the link is using, not the saved one: the
        // saved one may already have been corrected after the link started.
        val target = _active.value?.takeIf { it.target.fingerprint == found.fingerprint }?.target ?: return
        val moved = target.host != found.host || target.port != found.port
        if (moved && _active.value?.status?.value !is DaemonConnection.Status.Connected) {
            connect(store.find(found.fingerprint) ?: return)
        }
    }

    @Synchronized
    private fun start(target: Target, code: String?) {
        // Swap in the new link before stopping the old one, so the connection
        // service never sees a moment with no link and stops itself.
        val old = _active.value
        historyJob?.cancel()
        history.clear()
        // While pairing, send the code even if an old token is stored: the PC
        // may have unpaired this phone. Once a token arrives, use it instead.
        var pairingCode = code
        val connection = DaemonConnection(
            target,
            scope,
            hello = {
                Hello(
                    deviceId = store.deviceId,
                    deviceName = deviceName,
                    token = if (pairingCode == null) store.find(target.fingerprint)?.token else null,
                    code = pairingCode,
                )
            },
            onPaired = { token, welcome ->
                store.save(PairedPc(target.fingerprint, welcome.hostname, target.host, target.port, token))
                _activeName.value = welcome.hostname
                store.rememberLast(target.fingerprint)
                pairingCode = null
            },
        )
        _active.value = connection
        old?.stop()
        historyJob = scope.launch { connection.stats.filterNotNull().collect(history::add) }
        connection.start()
        ConnectionService.start(context)
    }

    private companion object {
        const val DISCOVERY_LINGER_MS = 5_000L
    }
}
