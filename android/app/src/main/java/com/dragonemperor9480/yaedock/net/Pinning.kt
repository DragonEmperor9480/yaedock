package com.dragonemperor9480.yaedock.net

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** Lowercase hex SHA-256, the fingerprint format the daemon shows. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Normalises a fingerprint typed or scanned in any common format. */
fun normalizeFingerprint(fingerprint: String): String =
    fingerprint.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }

/**
 * Trusts exactly one certificate: the daemon's self-signed one, identified by
 * the fingerprint from the pairing QR code. No certificate authority is involved.
 */
class PinnedTrustManager(fingerprint: String) : X509TrustManager {
    private val expected = normalizeFingerprint(fingerprint)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("no certificate")
        if (sha256Hex(leaf.encoded) != expected) {
            throw CertificateException("certificate does not match the paired PC")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** An OkHttp client that only talks to the daemon with this certificate fingerprint. */
fun pinnedClient(fingerprint: String): OkHttpClient {
    val trustManager = PinnedTrustManager(fingerprint)
    val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, trustManager)
        // The certificate itself is pinned, and the PC is reached by IP address,
        // so the host name check adds nothing.
        .hostnameVerifier { _, _ -> true }
        // The PC is on the local network, so it answers quickly or not at all.
        .connectTimeout(5, TimeUnit.SECONDS)
        // Liveness is handled by protocol pings, not by read timeouts.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
