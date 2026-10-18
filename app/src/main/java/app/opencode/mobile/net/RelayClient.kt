package app.opencode.mobile.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RelayClient(private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = false }

    private val ok = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RelayResponse>>()
    private val idGen = AtomicLong(0)

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    val state: StateFlow<RelayState> = _state

    private val _events = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JsonObject> = _events

    private val _agentOnline = MutableStateFlow(false)
    val agentOnline: StateFlow<Boolean> = _agentOnline

    @Volatile private var url = ""
    @Volatile private var token = ""
    @Volatile private var intent = false
    private var backoffMs = 1000L
    private var reconnectJob: Job? = null

    fun connect(url: String, token: String) {
        this.url = url.trim().trimEnd('/')
        this.token = token.trim()
        intent = true
        backoffMs = 1000L
        reconnectJob?.cancel()
        open()
    }

    fun disconnect() {
        intent = false
        reconnectJob?.cancel()
        _agentOnline.value = false
        _state.value = RelayState.Idle
        failAllPending("disconnected")
        ws?.close(1000, "bye")
        ws = null
    }

    private fun open() {
        if (!intent) return
        _state.value = RelayState.Connecting(url)
        val request = Request.Builder().url(buildWsUrl(url, token)).build()
        ws = ok.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleMessage(bytes.utf8())

            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoffMs = 1000L
                _state.value = RelayState.Ready(url)
                webSocket.send("""{"t":"ping"}""")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!intent) return
                _state.value = RelayState.Failed(t.message ?: "connection failed")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _agentOnline.value = false
                failAllPending("connection closed")
                if (!intent) return
                _state.value = RelayState.Failed(reason.ifEmpty { "connection closed" })
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!intent) return
        val delayMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (intent) open()
        }
    }

    private fun buildWsUrl(base: String, token: String): String {
        var u = base
        u = u.replaceFirst(Regex("^http://"), "ws://")
        u = u.replaceFirst(Regex("^https://"), "wss://")
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) u = "wss://$u"
        val sep = if (u.contains("?")) "&" else "?"
        return "$u${sep}role=client&token=$token"
    }

    private fun handleMessage(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        when (obj.str("t")) {
            "res" -> {
                val id = obj.str("id") ?: return
                val d = pending.remove(id) ?: return
                val status = obj.int("s") ?: 200
                val body = obj["b"] as? JsonElement ?: JsonNull
                d.complete(RelayResponse(status, body))
            }
            "ev" -> {
                val payload = obj["d"] as? JsonObject ?: return
                _events.tryEmit(payload)
            }
            "presence" -> _agentOnline.value = (obj.int("agents") ?: 0) > 0
            "pong" -> {}
        }
    }

    suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        timeoutSeconds: Long = 45L,
    ): RelayResponse {
        val socket = ws ?: throw RelayException("not connected")
        if (_state.value !is RelayState.Ready) throw RelayException("not connected")
        val id = idGen.incrementAndGet().toString()
        val deferred = CompletableDeferred<RelayResponse>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("t", "req")
            put("id", id)
            put("m", method)
            put("p", path)
            if (body != null) put("b", body)
        }
        if (!socket.send(msg.toString())) {
            pending.remove(id)
            throw RelayException("send failed")
        }
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutSeconds * 1000) { deferred.await() }
            ?: run {
                pending.remove(id)
                throw RelayException("timeout after ${timeoutSeconds}s")
            }
        if (result.status == 503) {
            throw RelayException("PC agent offline")
        }
        return result
    }

    private fun failAllPending(reason: String) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.completeExceptionally(RelayException(reason))
            it.remove()
        }
    }
}

class RelayException(message: String) : Exception(message)

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { if (it is JsonNull) null else it.content }

internal fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
