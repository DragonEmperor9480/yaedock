package com.dragonemperor9480.yaedock.net

import android.os.SystemClock
import com.dragonemperor9480.yaedock.protocol.CheckReport
import com.dragonemperor9480.yaedock.protocol.ClientMessage
import com.dragonemperor9480.yaedock.protocol.ClientPing
import com.dragonemperor9480.yaedock.protocol.ClientPong
import com.dragonemperor9480.yaedock.protocol.Codec
import com.dragonemperor9480.yaedock.protocol.Error
import com.dragonemperor9480.yaedock.protocol.ErrorCode
import com.dragonemperor9480.yaedock.protocol.Hello
import com.dragonemperor9480.yaedock.protocol.MediaArt
import com.dragonemperor9480.yaedock.protocol.MetricGroup
import com.dragonemperor9480.yaedock.protocol.RecordingSaved
import com.dragonemperor9480.yaedock.protocol.RunCheck
import com.dragonemperor9480.yaedock.protocol.Screenshot
import com.dragonemperor9480.yaedock.protocol.ServerPing
import com.dragonemperor9480.yaedock.protocol.ServerPong
import com.dragonemperor9480.yaedock.protocol.State
import com.dragonemperor9480.yaedock.protocol.Stats
import com.dragonemperor9480.yaedock.protocol.Subscribe
import com.dragonemperor9480.yaedock.protocol.UpdatePc
import com.dragonemperor9480.yaedock.protocol.UpdateProgress
import com.dragonemperor9480.yaedock.protocol.UpdateStage
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
    val target: Target,
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

    /** [state] with the time its media position arrived. */
    private fun stamped(state: State): State =
        state.copy(media = state.media?.stamped(_state.value?.media, SystemClock.elapsedRealtime()))

    /** Errors the daemon reports for commands, such as a failed volume change. */
    private val _errors = MutableSharedFlow<Error>(extraBufferCapacity = 8)
    val errors: SharedFlow<Error> = _errors.asSharedFlow()

    /** Screenshots the PC saved when asked to. */
    private val _screenshots = MutableSharedFlow<Screenshot>(extraBufferCapacity = 8)
    val screenshots: SharedFlow<Screenshot> = _screenshots.asSharedFlow()

    /** The cover sent last; [State.media] says whether it is still the one playing. */
    private val _art = MutableStateFlow<MediaArt?>(null)
    val art: StateFlow<MediaArt?> = _art.asStateFlow()

    /** Recordings the PC saved when asked to stop. */
    private val _recordings = MutableSharedFlow<RecordingSaved>(extraBufferCapacity = 8)
    val recordings: SharedFlow<RecordingSaved> = _recordings.asSharedFlow()

    /** What [runCheck] found, or why it could not run. */
    sealed interface CheckAnswer {
        data class Report(val report: CheckReport) : CheckAnswer
        data class Failed(val message: String) : CheckAnswer
        /** The daemon predates the check. */
        data object Unsupported : CheckAnswer
    }

    private val _checks = MutableSharedFlow<CheckAnswer>(extraBufferCapacity = 4)
    val checks: SharedFlow<CheckAnswer> = _checks.asSharedFlow()

    /** Where an update of the PC asked for with [updatePc] has got. */
    sealed interface PcUpdate {
        data object Idle : PcUpdate
        /** Under way; [percent] while downloading. */
        data class Running(val stage: UpdateStage?, val percent: Int?) : PcUpdate
        data class Failed(val message: String) : PcUpdate
        /** The PC came back on [version]. */
        data class Done(val version: String) : PcUpdate
    }

    private val _pcUpdate = MutableStateFlow<PcUpdate>(PcUpdate.Idle)
    val pcUpdate: StateFlow<PcUpdate> = _pcUpdate.asStateFlow()

    /**
     * Asks the PC to update itself to [version]; [pcUpdate] follows along,
     * through the restart and the reconnect after it.
     */
    fun updatePc(version: String): Boolean {
        val sent = send(UpdatePc(version))
        if (sent) _pcUpdate.value = PcUpdate.Running(null, null)
        return sent
    }

    /** Whether [error] is an update failing: while one runs, the PC's errors are its. */
    private fun answersUpdate(error: Error): Boolean =
        _pcUpdate.value is PcUpdate.Running && error.code in setOf(ErrorCode.BACKEND_FAILED, ErrorCode.BAD_REQUEST)

    /** Whether a check was asked for and has not been answered yet. */
    @Volatile private var checking = false

    private var job: Job? = null
    private val retryNow = Channel<Unit>(Channel.CONFLATED)

    /**
     * Whether nothing shows the stats now (the app is in the background or
     * the screen is off). The PC is then asked for none, which spares the
     * phone's battery and the PC's GPUs; changes to its state still arrive.
     */
    @Volatile private var background = false

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

    /** Asks the PC for every stat while something shows them, and for none otherwise. */
    fun setBackground(background: Boolean) {
        if (this.background == background) return
        this.background = background
        send(subscription())
    }

    private fun subscription() = if (background) {
        Subscribe(emptyList(), BACKGROUND_INTERVAL_MS)
    } else {
        Subscribe(MetricGroup.entries, FOREGROUND_INTERVAL_MS)
    }

    /** Skips the backoff delay, for example when the network comes back. */
    fun retryNow() {
        retryNow.trySend(Unit)
    }

    /** Closes the current link because the network under it is gone; reconnecting follows as usual. */
    fun dropLink() {
        socket?.cancel()
    }

    /** Sends a message if connected; returns whether it was queued. */
    fun send(message: ClientMessage): Boolean {
        val ws = socket ?: return false
        if (_status.value !is Status.Connected) return false
        lastSent = System.currentTimeMillis()
        return ws.send(Codec.encode(message))
    }

    /** Asks the PC to run its system check; the answer arrives on [checks]. */
    fun runCheck(): Boolean {
        checking = true
        return send(RunCheck).also { if (!it) checking = false }
    }

    /**
     * Whether [error] answers a pending [runCheck]: a daemon that does not know
     * the message says so with bad_request, and a check that failed with
     * backend_failed.
     */
    private fun answersCheck(error: Error): Boolean = checking && when (error.code) {
        ErrorCode.BAD_REQUEST -> "run_check" in error.message
        ErrorCode.BACKEND_FAILED -> true
        else -> false
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
                            _state.value = stamped(message.state)
                            // The PC restarted on the new version after an update.
                            if (_pcUpdate.value.let { it is PcUpdate.Running && it.stage == UpdateStage.RESTARTING }) {
                                _pcUpdate.value = PcUpdate.Done(message.daemonVersion)
                            }
                            _status.value = Status.Connected(message)
                            // The PC starts with every stat.
                            if (background) send(subscription())
                        }
                        // Stats asked for in the background carry nothing; the
                        // last full ones stay until new ones come.
                        is Stats -> if (message.hasMetrics) _stats.value = message
                        is State -> _state.value = stamped(message)
                        is Screenshot -> _screenshots.tryEmit(message)
                        is RecordingSaved -> _recordings.tryEmit(message)
                        is MediaArt -> _art.value = message
                        is UpdateProgress -> _pcUpdate.value = PcUpdate.Running(message.stage, message.percent)
                        is CheckReport -> {
                            checking = false
                            _checks.tryEmit(CheckAnswer.Report(message))
                        }
                        is Error ->
                            if (!welcomed && message.code in FATAL) {
                                return Outcome.Rejected(message)
                            } else if (answersUpdate(message)) {
                                val text = if (message.code == ErrorCode.BAD_REQUEST) {
                                    "This PC's YaeDock is too old to update from the phone; run yaedock update on it"
                                } else {
                                    message.message
                                }
                                _pcUpdate.value = PcUpdate.Failed(text)
                            } else if (answersCheck(message)) {
                                checking = false
                                val answer = if (message.code == ErrorCode.BAD_REQUEST) {
                                    CheckAnswer.Unsupported
                                } else {
                                    CheckAnswer.Failed(message.message)
                                }
                                _checks.tryEmit(answer)
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
            // A check asked over this link gets no answer now.
            checking = false
            // An update cut off before the restart will not say how it went.
            if (_pcUpdate.value.let { it is PcUpdate.Running && it.stage != UpdateStage.RESTARTING }) {
                _pcUpdate.value = PcUpdate.Failed("The connection to the PC dropped during the update")
            }
        }
    }

    companion object {
        const val KEEPALIVE_MS = 10_000L
        const val SILENCE_MS = 30_000L
        const val FOREGROUND_INTERVAL_MS = 1_000
        /** Stats in the background come this seldom; the PC pings every 10 s regardless. */
        const val BACKGROUND_INTERVAL_MS = 60_000
        private val FATAL = setOf(ErrorCode.UNAUTHORIZED, ErrorCode.UNSUPPORTED_VERSION)

        /** 1 s, 2 s, 4 s, ... up to 30 s. */
        fun backoffMs(attempt: Int): Long = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
    }
}
