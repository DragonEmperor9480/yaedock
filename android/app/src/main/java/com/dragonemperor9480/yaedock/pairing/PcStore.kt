package com.dragonemperor9480.yaedock.pairing

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A PC this phone has paired with. The certificate fingerprint identifies it. */
@Serializable
data class PairedPc(
    val fingerprint: String,
    val name: String,
    /** Last known address; mDNS discovery updates it when the PC moves. */
    val host: String,
    val port: Int,
    val token: String,
)

/**
 * Paired PCs and this phone's device id, kept as small files in the app's
 * private storage ([dir] is normally `Context.filesDir`).
 */
class PcStore(private val dir: File) {
    private val pcsFile = File(dir, "paired_pcs.json")
    private val lastPcFile = File(dir, "last_pc")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PairedPc.serializer())

    private val _pcs = MutableStateFlow(load())
    val pcs: StateFlow<List<PairedPc>> = _pcs.asStateFlow()

    /** A random id generated on first use, sent as `device_id` in every hello. */
    val deviceId: String by lazy {
        val file = File(dir, "device_id")
        file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString().also { writeAtomically(file, it) }
    }

    fun find(fingerprint: String): PairedPc? = _pcs.value.find { it.fingerprint == fingerprint }

    /** The PC to connect to when the app opens: the last one used, or else the only one. */
    fun last(): PairedPc? =
        lastPcFile.takeIf { it.exists() }?.readText()?.trim()?.let(::find) ?: _pcs.value.singleOrNull()

    fun rememberLast(fingerprint: String) = writeAtomically(lastPcFile, fingerprint)

    /** Adds a PC, or replaces the one with the same fingerprint. */
    fun save(pc: PairedPc) = change { pcs -> pcs.filter { it.fingerprint != pc.fingerprint } + pc }

    fun remove(fingerprint: String) = change { pcs -> pcs.filter { it.fingerprint != fingerprint } }

    /** Records a new address for a paired PC, found through mDNS. */
    fun updateAddress(fingerprint: String, host: String, port: Int) = change { pcs ->
        pcs.map { if (it.fingerprint == fingerprint) it.copy(host = host, port = port) else it }
    }

    @Synchronized
    private fun change(transform: (List<PairedPc>) -> List<PairedPc>) {
        _pcs.update { current ->
            transform(current).also { if (it != current) writeAtomically(pcsFile, json.encodeToString(serializer, it)) }
        }
    }

    private fun load(): List<PairedPc> =
        runCatching { json.decodeFromString(serializer, pcsFile.readText()) }.getOrDefault(emptyList())

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
