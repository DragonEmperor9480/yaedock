package com.dragonemperor9480.yaedock.net

import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.ClientPing
import com.dragonemperor9480.yaedock.protocol.ClientPong
import com.dragonemperor9480.yaedock.protocol.Codec
import com.dragonemperor9480.yaedock.protocol.Error
import com.dragonemperor9480.yaedock.protocol.ErrorCode
import com.dragonemperor9480.yaedock.protocol.Hello
import com.dragonemperor9480.yaedock.protocol.ServerPing
import com.dragonemperor9480.yaedock.protocol.ServerPong
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Where a PC is and how to recognise it. */
data class Target(val host: String, val port: Int, val fingerprint: String)

/**
 * One phone-to-PC link that keeps itself up: it says hello, answers pings,
 * pings when idle, and reconnects with backoff after the link drops, until the
 * daemon rejects the phone or [stop] is called.
 *
 * [hello] is called for every attempt, so a token received while pairing is
 * used on the next reconnect. [onPaired] receives that token, with the welcome
 * it came in, so it can be stored.
 */
class DaemonConnection(
    private val target: Target,
    private val scope: CoroutineScope,
    private val hello: () -> Hello,
    private val onPaired: (token: String, welcome: Welcome) -> Unit,
    private val client: OkHttpClient = pinnedClient(target.fingerprint),
) {
    sealed interface Status {
        data object Connecting : Status
        data class Connected(val welcome: Welcome) : Status
        /** The link dropped; the next attempt starts in [retryInMs]. */
        data class Waiting(val reason: String, val retryInMs: Long) : Status
        /** The daemon refused this phone; no further attempts are made. */
        data class Rejected(val error: Error) : Status
        data object Stopped : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _stats = MutableStateFlow<Stats?>(null)
    val stats: StateFlow<Stats?> = _stats.asStateFlow()

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Errors the daemon reports for commands, such as a failed volume change. */
    private val _errors = MutableSharedFlow<Error>(extraBufferCapacity = 8)
    val errors: SharedFlow<Error> = _errors.asSharedFlow()

    private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastSent = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, null)
        socket = null
        _status.value = Status.Stopped
    }

    /** Skips the backoff delay, for example when the network comes back. */
    fun retryNow() {
        retryNow.trySend(Unit)
    }

    /** Sends a message if connected; returns whether it was queued. */
    fun send(message: ClientMessage): Boolean {
        val ws = socket ?: return false
        if (_status.value !is Status.Connected) return false
        lastSent = System.currentTimeMillis()
        return ws.send(Codec.encode(message))
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            _status.value = Status.Connecting
            when (val outcome = connectOnce()) {
                is Outcome.Rejected -> {
                    _status.value = Status.Rejected(outcome.error)
                    return
                }
                is Outcome.Lost -> {
                    if (outcome.wasConnected) attempt = 0
                    val wait = backoffMs(attempt++)
                    _status.value = Status.Waiting(outcome.reason, wait)
                    select<Unit> {
                        retryNow.onReceive { }
                        onTimeout(wait) { }
                    }
                }
            }
        }
    }

    private sealed interface Outcome {
        data class Rejected(val error: Error) : Outcome
        data class Lost(val reason: String, val wasConnected: Boolean) : Outcome
    }

    private sealed interface Event {
        data object Open : Event
        data class Text(val text: String) : Event
        data class Closed(val reason: String) : Event
    }

    private suspend fun connectOnce(): Outcome {
        val events = Channel<Event>(Channel.UNLIMITED)
        val request = Request.Builder().url("wss://${target.host}:${target.port}").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(Event.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(Event.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                events.trySend(Event.Closed("closed by the PC"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(Event.Closed(t.message ?: t.javaClass.simpleName))
            }
        })
        socket = ws
        var welcomed = false
        val keepalive = scope.launch {
            while (true) {
                delay(KEEPALIVE_MS)
                if (welcomed && System.currentTimeMillis() - lastSent >= KEEPALIVE_MS) send(ClientPing)
            }
        }
        try {
            while (true) {
                // Stats arrive every second, so this much silence means the link is dead.
                val event = withTimeoutOrNull(SILENCE_MS) { events.receive() }
                    ?: return Outcome.Lost("no data from the PC", welcomed)
                when (event) {
                    is Event.Closed -> return Outcome.Lost(event.reason, welcomed)
                    Event.Open -> {
                        lastSent = System.currentTimeMillis()
                        ws.send(Codec.encode(hello()))
                    }
                    is Event.Text -> when (val message = Codec.decode(event.text)) {
                        is Welcome -> {
                            welcomed = true
                            message.token?.let { onPaired(it, message) }
                            _state.value = message.state
                            _status.value = Status.Connected(message)
                        }
                        is Stats -> _stats.value = message
                        is State -> _state.value = message
                        is Error ->
                            if (!welcomed && message.code in FATAL) {
                                return Outcome.Rejected(message)
                            } else {
                                _errors.tryEmit(message)
                            }
                        ServerPing -> send(ClientPong)
                        ServerPong, null -> {}
                    }
                }
            }
        } finally {
            keepalive.cancel()
            ws.cancel()
            socket = null
        }
    }

    companion object {
        const val KEEPALIVE_MS = 10_000L
        const val SILENCE_MS = 30_000L
        private val FATAL = setOf(ErrorCode.UNAUTHORIZED, ErrorCode.UNSUPPORTED_VERSION)

        /** 1 s, 2 s, 4 s, ... up to 30 s. */
        fun backoffMs(attempt: Int): Long = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
    }
}
