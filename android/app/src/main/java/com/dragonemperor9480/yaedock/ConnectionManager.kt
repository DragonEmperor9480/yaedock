package com.dragonemperor9480.yaedock

import android.os.Build
import com.dragonemperor9480.yaedock.net.DaemonConnection
import com.dragonemperor9480.yaedock.net.Target
import com.dragonemperor9480.yaedock.pairing.PairedPc
import com.dragonemperor9480.yaedock.pairing.PairingInfo
import com.dragonemperor9480.yaedock.pairing.PcStore
import com.dragonemperor9480.yaedock.protocol.Hello
import com.dragonemperor9480.yaedock.ui.StatsHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Owns the one active link to a PC, and the stats history it feeds. */
class ConnectionManager(private val store: PcStore, private val scope: CoroutineScope) {
    private val _active = MutableStateFlow<DaemonConnection?>(null)
    val active: StateFlow<DaemonConnection?> = _active.asStateFlow()

    val history = StatsHistory()
    private var historyJob: Job? = null

    private val deviceName: String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { !it.isNullOrBlank() }
        .joinToString(" ")
        .ifEmpty { "Android phone" }

    /** Connects to a PC that is already paired. */
    fun connect(pc: PairedPc) = start(Target(pc.host, pc.port, pc.fingerprint), code = null)

    /**
     * Pairs with a new PC using the code from its QR code. Once the daemon
     * returns a token the PC is saved, and reconnects use the token.
     */
    fun pair(info: PairingInfo) = start(Target(info.host, info.port, info.fingerprint), code = info.code)

    fun disconnect() {
        historyJob?.cancel()
        _active.value?.stop()
        _active.value = null
    }

    private fun start(target: Target, code: String?) {
        disconnect()
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
                pairingCode = null
            },
        )
        _active.value = connection
        historyJob = scope.launch { connection.stats.filterNotNull().collect(history::add) }
        connection.start()
    }
}
