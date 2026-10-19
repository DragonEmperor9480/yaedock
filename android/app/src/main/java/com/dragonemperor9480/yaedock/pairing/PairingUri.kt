package com.dragonemperor9480.yaedock.pairing

import com.dragonemperor9480.yaedock.net.normalizeFingerprint
import java.net.URI
import java.net.URLDecoder

/** What the pairing QR code from `yaedockd pair` holds. */
data class PairingInfo(
    val host: String,
    val port: Int,
    /** Lowercase hex SHA-256 of the daemon's certificate. */
    val fingerprint: String,
    /** One-time 6-digit code. */
    val code: String,
)

object PairingUri {
    const val DEFAULT_PORT = 47820

    /**
     * Parses `yaedock://pair?host=...&port=...&fp=...&code=...`, as described
     * in protocol/protocol.md. Returns null for anything else.
     */
    fun parse(text: String): PairingInfo? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "yaedock" || uri.host != "pair") return null
        val query = uri.rawQuery ?: return null
        val params = query.split('&').mapNotNull { pair ->
            val (key, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            key to URLDecoder.decode(value, "UTF-8")
        }.toMap()
        return validate(
            host = params["host"],
            port = params["port"] ?: DEFAULT_PORT.toString(),
            fingerprint = params["fp"],
            code = params["code"],
        )
    }

    /** Checks details typed by hand, accepting the spaced formats `yaedockd pair` prints. */
    fun validate(host: String?, port: String?, fingerprint: String?, code: String?): PairingInfo? {
        val cleanHost = host?.trim()?.takeIf { it.isNotEmpty() && ' ' !in it } ?: return null
        val cleanPort = port?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val cleanFingerprint = fingerprint?.let(::normalizeFingerprint)?.takeIf { it.length == 64 } ?: return null
        val cleanCode = code?.filter { !it.isWhitespace() }?.takeIf { it.length == 6 && it.all(Char::isDigit) }
            ?: return null
        return PairingInfo(cleanHost, cleanPort, cleanFingerprint, cleanCode)
    }
}
