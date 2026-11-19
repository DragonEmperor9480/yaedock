package com.dragonemperor9480.yaedock.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
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
        .socketFactory(LeanSockets)
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

/**
 * Sockets that keep little waiting to be sent. The phone streams sound and
 * video to the PC, and what the system holds for a slow link is out of the
 * app's sight: left at its usual size, that is seconds of video, sent late
 * once the link recovers. Held small, the app sees the link is slow and drops
 * pictures instead.
 */
private object LeanSockets : SocketFactory() {
    private const val SEND_BUFFER_BYTES = 32 * 1024
    private val plain = getDefault()

    private fun lean(socket: Socket) = socket.apply {
        runCatching { sendBufferSize = SEND_BUFFER_BYTES }
        // Each message goes out as it is written, not held for the next to fill a packet.
        runCatching { tcpNoDelay = true }
    }

    override fun createSocket(): Socket = lean(plain.createSocket())
    override fun createSocket(host: String, port: Int): Socket = lean(plain.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        lean(plain.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = lean(plain.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        lean(plain.createSocket(address, port, localAddress, localPort))
}
