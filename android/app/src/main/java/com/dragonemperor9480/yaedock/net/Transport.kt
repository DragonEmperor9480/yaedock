package com.dragonemperor9480.yaedock.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A way to reach the PC: Wi-Fi now; a cable and Bluetooth later. Every way
 * carries the same messages, so [DaemonConnection] does not care which one it
 * is given.
 */
interface Transport {
    /**
     * Starts opening a link and returns at once; [events] hears how it goes.
     * Each call makes a new link, for a new attempt.
     */
    fun open(events: LinkEvents): Link
}

/** What a [Link] tells its user. Called on the link's own thread. */
interface LinkEvents {
    /** The link is up and messages can be sent. */
    fun onOpen()

    /** One protocol message, as JSON text. */
    fun onText(text: String)

    /** One binary frame, such as a picture of the PC's screen. */
    fun onBinary(bytes: ByteArray)

    /** The link is gone, for [reason]; nothing more is heard from it. */
    fun onClosed(reason: String)
}

/** What a link runs over. */
enum class Via { WIFI, CABLE }

/** One open (or opening) link to the PC. */
interface Link {
    /** What it runs over. */
    val via: Via get() = Via.WIFI

    /** Queues one protocol message; false if the link can take no more. */
    fun sendText(text: String): Boolean

    /** Queues one binary frame, such as sound or a picture; false if the link can take no more. */
    fun sendBinary(bytes: ByteArray): Boolean

    /** How many bytes are queued and not yet handed to the network. */
    fun queued(): Long

    /** Closes the link after what is queued has gone. */
    fun close()

    /** Drops the link now, along with what is queued. */
    fun cancel()
}

/** The PC over the network: a TLS WebSocket to [target], trusting only its certificate. */
class WebSocketTransport(
    private val target: Target,
    private val client: OkHttpClient = pinnedClient(target.fingerprint),
) : Transport {
    override fun open(events: LinkEvents): Link {
        val request = Request.Builder().url("wss://${target.host}:${target.port}").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = events.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = events.onText(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = events.onBinary(bytes.toByteArray())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL, null)
                events.onClosed("closed by the PC")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                events.onClosed(t.message ?: t.javaClass.simpleName)
        })
        return object : Link {
            override val via = Via.WIFI
            override fun sendText(text: String) = ws.send(text)
            override fun sendBinary(bytes: ByteArray) = ws.send(ByteString.of(*bytes))
            override fun queued() = ws.queueSize()
            override fun close() {
                ws.close(NORMAL, null)
            }
            override fun cancel() = ws.cancel()
        }
    }

    private companion object {
        /** WebSocket's "normal closure". */
        const val NORMAL = 1000
    }
}

/** Uses the cable when [cable] offers one for this PC, and Wi-Fi otherwise; asked anew for every link. */
class ChoosingTransport(private val wifi: Transport, private val cable: () -> Transport?) : Transport {
    override fun open(events: LinkEvents): Link = (cable() ?: wifi).open(events)
}
