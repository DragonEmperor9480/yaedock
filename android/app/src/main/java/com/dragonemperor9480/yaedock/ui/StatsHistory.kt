package com.dragonemperor9480.yaedock.ui

import com.dragonemperor9480.yaedock.protocol.Stats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The most recent samples of each metric, oldest first, for sparklines. */
data class History(
    val cpu: List<Float> = emptyList(),
    /** Memory used, in percent. */
    val mem: List<Float> = emptyList(),
    val netRx: List<Float> = emptyList(),
    val netTx: List<Float> = emptyList(),
    val diskRead: List<Float> = emptyList(),
    val diskWrite: List<Float> = emptyList(),
    val temps: Map<String, List<Float>> = emptyMap(),
    /** GPU usage in percent, by GPU id. */
    val gpus: Map<String, List<Float>> = emptyMap(),
)

/** Keeps the last [capacity] samples, one per stats message (normally 60 s). */
class StatsHistory(private val capacity: Int = 60) {
    private val _history = MutableStateFlow(History())
    val history: StateFlow<History> = _history.asStateFlow()

    fun clear() {
        _history.value = History()
    }

    fun add(stats: Stats) {
        val h = _history.value
        _history.value = History(
            cpu = h.cpu.push(stats.cpu?.total),
            mem = h.mem.push(stats.mem?.let { if (it.total > 0) 100f * it.used / it.total else null }),
            netRx = h.netRx.push(stats.net?.rx?.toFloat()),
            netTx = h.netTx.push(stats.net?.tx?.toFloat()),
            diskRead = h.diskRead.push(stats.disk?.read?.toFloat()),
            diskWrite = h.diskWrite.push(stats.disk?.write?.toFloat()),
            temps = stats.temps.orEmpty().mapValues { (name, value) -> h.temps[name].orEmpty().push(value) },
            gpus = stats.gpus.orEmpty().associate { gpu -> gpu.id to h.gpus[gpu.id].orEmpty().push(gpu.usage) },
        )
    }

    private fun List<Float>.push(value: Float?): List<Float> =
        if (value == null) this else (this + value).takeLast(capacity)
}
