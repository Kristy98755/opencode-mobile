package kanami.isa.opencode.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kanami.isa.opencode.connect.data.CachedChat
import kanami.isa.opencode.connect.data.ChatCacheStore
import kanami.isa.opencode.connect.data.SettingsRepository
import kanami.isa.opencode.connect.net.AgentInfo
import kanami.isa.opencode.connect.net.ApiException
import kanami.isa.opencode.connect.net.FileAttachment
import kanami.isa.opencode.connect.net.FileContent
import kanami.isa.opencode.connect.net.FileNode
import kanami.isa.opencode.connect.net.MessageInfo
import kanami.isa.opencode.connect.net.MessageWithParts
import kanami.isa.opencode.connect.net.MessagesPage
import kanami.isa.opencode.connect.net.ModelRef
import kanami.isa.opencode.connect.net.OpencodeApi
import kanami.isa.opencode.connect.net.Part
import kanami.isa.opencode.connect.net.PathInfo
import kanami.isa.opencode.connect.net.ProvidersResponse
import kanami.isa.opencode.connect.net.RelayClient
import kanami.isa.opencode.connect.net.RelayException
import kanami.isa.opencode.connect.net.RelayState
import kanami.isa.opencode.connect.net.Session
import kanami.isa.opencode.connect.net.TAIL_CHUNK_CHARS
import kanami.isa.opencode.connect.net.json
import kanami.isa.opencode.connect.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "OCM"

/** Diagnostics for lost polls/reconnects — view with `adb logcat -s OCM`. */
private fun dbg(msg: String) {
    android.util.Log.d(TAG, msg)
}

data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String>,
    val metadata: JsonObject?,
    val always: List<String>,
)

data class QuestionRequest(
    val id: String,
    val sessionID: String,
    val questions: List<JsonObject>,
)

data class ChatUiState(
    val sessionId: String? = null,
    val title: String? = null,
    val model: ModelRef? = null,
    val messages: List<MessageWithParts> = emptyList(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val retryPrompt: String? = null,
    val draft: String = "",
    val awaitingUpstream: Boolean = false,
    val pendingUserMsgId: String? = null,
    val hasMore: Boolean = true,
    val loadingMore: Boolean = false,
    val oldestLoadedId: String? = null,
)

data class FilesUiState(
    val path: String = "",
    val nodes: List<FileNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val sort: String = "name",
)

data class ViewerState(
    val path: String,
    val content: String,
    val binary: Boolean,
)

/** Copy/move held while the browser walks to the destination folder. */
data class PendingOp(
    val kind: String, // "copy" | "move"
    val paths: List<String>,
    val name: String,
)

data class DownloadUiState(
    val name: String = "",
    val total: Long = 0,
    val done: Long = 0,
    val running: Boolean = true,
    val error: String? = null,
)

/** Finished download waiting for the user to pick Open / Share / Close. */
data class DownloadDone(
    val name: String,
    val uri: android.net.Uri,
    val mime: String,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    val relay = RelayClient(viewModelScope)
    val api = OpencodeApi(relay)

    private val _relayState = relay.state
    val relayState: StateFlow<RelayState> = _relayState

    private val _agentOnline = relay.agentOnline
    val agentOnline: StateFlow<Boolean> = _agentOnline

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _sessionsLoading = MutableStateFlow(false)
    val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    private val _chat = MutableStateFlow(ChatUiState())
    val chat: StateFlow<ChatUiState> = _chat.asStateFlow()

    private val _files = MutableStateFlow(FilesUiState())
    val files: StateFlow<FilesUiState> = _files.asStateFlow()

    private val _viewer = MutableStateFlow<ViewerState?>(null)
    val viewer: StateFlow<ViewerState?> = _viewer.asStateFlow()

    private val _pendingOp = MutableStateFlow<PendingOp?>(null)
    val pendingOp: StateFlow<PendingOp?> = _pendingOp.asStateFlow()

    private val _download = MutableStateFlow<DownloadUiState?>(null)
    val download: StateFlow<DownloadUiState?> = _download.asStateFlow()

    private val _downloadDone = MutableStateFlow<DownloadDone?>(null)
    val downloadDone: StateFlow<DownloadDone?> = _downloadDone.asStateFlow()

    private val _fileOpBusy = MutableStateFlow(false)
    val fileOpBusy: StateFlow<Boolean> = _fileOpBusy.asStateFlow()

    private var downloadJob: Job? = null

    private val _providers = MutableStateFlow<ProvidersResponse?>(null)
    val providers: StateFlow<ProvidersResponse?> = _providers.asStateFlow()

    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    val agents: StateFlow<List<AgentInfo>> = _agents.asStateFlow()

    private val _permission = MutableStateFlow<PermissionRequest?>(null)
    val permission: StateFlow<PermissionRequest?> = _permission.asStateFlow()

    private val _question = MutableStateFlow<QuestionRequest?>(null)
    val question: StateFlow<QuestionRequest?> = _question.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    private val _selectedAgent = MutableStateFlow("")
    val selectedAgent: StateFlow<String> = _selectedAgent.asStateFlow()

    private val _showProjects = MutableStateFlow(false)
    val showProjects: StateFlow<Boolean> = _showProjects.asStateFlow()

    private val _openSessionRequest = MutableStateFlow<String?>(null)
    val openSessionRequest: StateFlow<String?> = _openSessionRequest.asStateFlow()

    private val draftMutex = Mutex()
    private var draftGeneration = 0
    private val cacheStore = ChatCacheStore(application)
    private val memCache = mutableMapOf<String, CachedChat>()

    private var eventJob: Job? = null
    private var sessionStatusJob: Job? = null
    private var stateCollectJob: Job? = null
    private var draftJob: Job? = null
    private val tailPollJobs = mutableMapOf<String, Job>()
    private var appInForeground = true
    private val busyBySession = mutableMapOf<String, Boolean>()
    private val _busySessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val busySessions: StateFlow<Map<String, Boolean>> = _busySessions.asStateFlow()
    private val handledQuestionIds = mutableSetOf<String>()
    private var questionSetAt = 0L
    private var lastSessionsHash: String? = null

    init {
        viewModelScope.launch {
            val (url, token) = settings.relayConfig()
            _selectedAgent.value = settings.agent.first()
            runCatching { _showProjects.value = settings.projectsMode() }
            if (url.isNotEmpty() && token.isNotEmpty()) {
                connectInternal(url, token)
            }
        }
        eventJob = viewModelScope.launch(Dispatchers.IO) {
            relay.events.collect { handleEvent(it) }
        }
    }

    fun connect(url: String, token: String) {
        viewModelScope.launch { settings.saveRelay(url, token) }
        connectInternal(url, token)
    }

    private fun connectInternal(url: String, token: String) {
        relay.connect(url, token)
        if (stateCollectJob != null) return
        stateCollectJob = viewModelScope.launch {
            relay.state.collect { st ->
                if (st is RelayState.Ready) {
                    dbg("relay ready — resuming sessions/polls")
                    refreshSessions()
                    loadMeta()
                    startStatusPolling()
                    refreshBusyFromServer()
                    refreshPendingQuestions()
                    val sid = _chat.value.sessionId
                    if (sid != null && (_chat.value.busy || _chat.value.awaitingUpstream)) {
                        // reconnect mid-host-turn: the tail poll died with the socket
                        startTailPolling(sid)
                    }
                    // any other session still marked busy lost its poll too
                    busyBySession.filterValues { it }.keys
                        .filter { it != sid }
                        .forEach { startTailPolling(it) }
                }
            }
        }
    }

    fun disconnect() {
        relay.disconnect()
    }

    fun toast(msg: String) {
        _toast.tryEmit(msg)
    }

    fun setForeground(foreground: Boolean) {
        val was = appInForeground
        appInForeground = foreground
        if (foreground && !was) {
            // Missed events while the socket was dead: re-read everything stateful.
            refreshSessions(force = true)
            refreshPendingQuestions()
            refreshBusyFromServer()
            // A frozen/killed process lost its socket and every poll coroutine:
            // make sure the relay is actually up, then resume the busy polls.
            viewModelScope.launch {
                val st = relay.state.value
                dbg("resume: relay=$st busy=${busyBySession.filterValues { it }.keys}")
                if (st !is RelayState.Ready && st !is RelayState.Connecting) {
                    val (url, token) = settings.relayConfig()
                    if (url.isNotEmpty() && token.isNotEmpty()) connectInternal(url, token)
                }
                busyBySession.filterValues { it }.keys.forEach { startTailPolling(it) }
                val sid = _chat.value.sessionId
                if (sid != null && (_chat.value.busy || _chat.value.awaitingUpstream)) {
                    startTailPolling(sid)
                }
            }
        }
    }

    suspend fun hasSavedCredentials(): Boolean {
        val (url, token) = settings.relayConfig()
        return url.isNotEmpty() && token.isNotEmpty()
    }

    private fun setBusy(sessionId: String, busy: Boolean) {
        val prev = busyBySession[sessionId]
        busyBySession[sessionId] = busy
        _busySessions.value = busyBySession.toMap()
        if (_chat.value.sessionId == sessionId) {
            _chat.value = _chat.value.copy(
                busy = busy,
                awaitingUpstream = if (busy) _chat.value.awaitingUpstream else false,
                // a live turn supersedes the "wake lost" banner
                error = if (busy) null else _chat.value.error,
            )
        }
        // Notify when a turn ends: app in background, or a *different* session
        // finishing while the user is busy elsewhere in the app (multisession).
        if (prev == true && !busy && (!appInForeground || _chat.value.sessionId != sessionId)) {
            val title = _sessions.value.find { it.id == sessionId }?.title
                ?: _chat.value.title
                ?: "OpenCode"
            Notifier.notifyFinished(getApplication(), title, "Agent finished writing", sessionId)
        }
    }

    /** Called when the user taps a notification for a finished session. */
    fun openSessionFromNotification(sessionId: String) {
        _openSessionRequest.value = sessionId
    }

    /**
     * Turns running on the PC (host-run) never show up in the status map — but
     * their lifecycle still streams through message.updated. Track it for EVERY
     * session, phone- or PC-side: a fresh assistant message means "running", one
     * with completed/error means "done", and setBusy() turns that transition into
     * the "agent finished" notification when the app is in the background.
     */
    private fun trackTurnSignal(mi: MessageInfo) {
        if (mi.role != "assistant") return
        val done = mi.time?.get("completed") != null || mi.error != null
        if (done) {
            if (busyBySession[mi.sessionID] == true) setBusy(mi.sessionID, false)
        } else if (busyBySession[mi.sessionID] != true) {
            setBusy(mi.sessionID, true)
        }
    }

    fun clearOpenSessionRequest() {
        _openSessionRequest.value = null
    }

    private fun errorText(payload: JsonObject): String {
        val err = payload["error"] as? JsonObject
        val dataErr = (err?.get("data") as? JsonObject)?.get("error") as? JsonObject
        val message = err?.str("message") ?: dataErr?.str("message") ?: payload.str("message")
        val title = err?.str("title") ?: dataErr?.str("title")
        val raw = when {
            message != null && title != null -> "$title: $message"
            message != null -> message
            title != null -> title
            else -> "session error"
        }
        // "Task k1 was cancelled" & friends — say what actually happened instead.
        return friendlySendError(raw)
    }

    // --- sessions ---

    fun refreshSessions(force: Boolean = false) {
        if (!force && _sessions.value.isNotEmpty()) return
        viewModelScope.launch {
            _sessionsLoading.value = true
            try {
                val list = api.sessions()
                val sorted = list.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
                val hash = sessionsHash(sorted)
                if (hash != lastSessionsHash) {
                    lastSessionsHash = hash
                    _sessions.value = sorted
                }
            } catch (e: Exception) {
                toastError("Sessions", e)
            } finally {
                _sessionsLoading.value = false
            }
        }
    }

    private fun sessionsHash(list: List<Session>): String =
        list.joinToString("|") { s ->
            "${s.id}:${s.time?.updated ?: 0}:${s.title ?: ""}:${s.parentID ?: ""}"
        }

    fun newSession(directory: String, onCreated: (Session) -> Unit) {
        viewModelScope.launch {
            try {
                val session = api.createSession(
                    agent = _selectedAgent.value.ifEmpty { null },
                    directory = directory.ifBlank { null },
                )
                if (_sessions.value.none { it.id == session.id }) {
                    _sessions.value = listOf(session) + _sessions.value
                }
                onCreated(session)
            } catch (e: Exception) {
                toastError("Create session", e)
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            try {
                api.deleteSession(id)
                _sessions.value = _sessions.value.filterNot { it.id == id }
                if (_chat.value.sessionId == id) closeChat()
            } catch (e: Exception) {
                toastError("Delete", e)
            }
        }
    }

    // --- chat ---

    fun openChat(session: Session) {
        pendingLocalId = null
        // Note: no tail-poll cancellation here — a previously opened session may
        // still be mid-turn and its poll must keep tracking it in the background.
        _chat.value = ChatUiState(
            sessionId = session.id,
            title = session.title,
            model = session.model,
            loading = true,
        )
        viewModelScope.launch {
            val cached = memCache[session.id] ?: cacheStore.load(session.id)
            // old caches could contain optimistic local- bubbles: drop them on load
            val cachedClean = cached?.copy(messages = cached.messages.filterNot { it.info.id.startsWith("local-") })
            if (cachedClean != null && cachedClean.messages.isNotEmpty()) {
                memCache[session.id] = cachedClean
                if (_chat.value.sessionId == session.id) {
                    _chat.value = _chat.value.copy(
                        messages = cachedClean.messages,
                        loading = false,
                        hasMore = cachedClean.hasMore,
                        oldestLoadedId = cachedClean.oldest,
                    )
                }
            }
            runCatching { settings.getDraft(session.id) }.getOrDefault("").let { saved ->
                if (saved.isNotEmpty() && _chat.value.sessionId == session.id) {
                    _chat.value = _chat.value.copy(draft = saved)
                }
            }
            runCatching {
                val st = api.sessionStatus()
                val s = (st[session.id] as? JsonObject)?.str("type")
                if (s == "busy" || s == "retry") {
                    setBusy(session.id, true)
                    startTailPolling(session.id)
                }
            }
            try {
                val page = api.messagesTail(session.id)
                // the await can outlast a session switch — never merge into a foreign chat
                if (_chat.value.sessionId != session.id) return@launch
                val merged = mergeMessageLists(_chat.value.messages, page.messages)
                _chat.value = _chat.value.copy(
                    messages = merged,
                    loading = false,
                    hasMore = page.hasMore,
                    oldestLoadedId = page.oldest,
                )
                scrollToBottomIfNeeded()
                persistCache(session.id)
                if (lastTurnInFlight(merged)) {
                    // the status map doesn't list desktop-CLI turns; the store does
                    setBusy(session.id, true)
                    startTailPolling(session.id)
                } else {
                    if (busyBySession[session.id] == true && promptSentAt == 0L) {
                        // status check above didn't see busy and no turn is in
                        // flight: the stale mark would show "working" forever
                        setBusy(session.id, false)
                    }
                    if (
                        busyBySession[session.id] != true &&
                        promptSentAt == 0L &&
                        merged.lastOrNull { !it.info.id.startsWith("local-") }?.info?.role == "user"
                    ) {
                        // Last message is ours and the server is idle: the admitted
                        // input never woke a turn (lost wake). Say so instead of
                        // showing a silent dead end — any new message kicks it.
                        if (_chat.value.sessionId == session.id && _chat.value.error == null) {
                            _chat.value = _chat.value.copy(
                                error = "The last message reached opencode, but its reply never " +
                                    "started. Send any message to wake the session.",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (_chat.value.sessionId == session.id) {
                    _chat.value = _chat.value.copy(
                        loading = false,
                        error = if (_chat.value.messages.isEmpty()) e.message ?: "load failed" else _chat.value.error,
                    )
                }
            }
        }
    }

    /** "Load more" plate: one older chunk, capped to TAIL_CHUNK_CHARS like the initial tail. */
    fun loadMoreMessages() {
        val cur = _chat.value
        val sid = cur.sessionId ?: return
        val before = cur.oldestLoadedId ?: return
        if (cur.loading || cur.loadingMore || !cur.hasMore) return
        _chat.value = cur.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = api.messagesPage(sid, before, chars = TAIL_CHUNK_CHARS)
                // the await can outlast a session switch — never merge into a foreign chat
                if (_chat.value.sessionId != sid) return@launch
                val merged = mergeMessageLists(_chat.value.messages, page.messages)
                _chat.value = _chat.value.copy(
                    messages = merged,
                    hasMore = page.hasMore,
                    oldestLoadedId = page.oldest,
                    loadingMore = false,
                )
                persistCache(sid)
            } catch (e: Exception) {
                _chat.value = _chat.value.copy(loadingMore = false)
            }
        }
    }

    /** The fragment plate inside a truncated message: fetch its full version, nothing else. */
    fun expandMessage() {
        val cur = _chat.value
        val sid = cur.sessionId ?: return
        val before = cur.oldestLoadedId ?: return
        if (cur.loading || cur.loadingMore) return
        _chat.value = cur.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = api.messagesPage(sid, before, limit = 1)
                if (_chat.value.sessionId != sid) return@launch
                val merged = mergeMessageLists(_chat.value.messages, page.messages)
                _chat.value = _chat.value.copy(
                    messages = merged,
                    hasMore = if (page.hasMore) page.hasMore else _chat.value.hasMore,
                    loadingMore = false,
                )
                persistCache(sid)
            } catch (e: Exception) {
                _chat.value = _chat.value.copy(loadingMore = false)
            }
        }
    }

    /** "Load full conversation": drain every remaining page untruncated until hasMore flips. */
    fun loadFullHistory() {
        val cur = _chat.value
        val sid = cur.sessionId ?: return
        if (cur.loading || cur.loadingMore || !cur.hasMore) return
        _chat.value = cur.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                var guard = 0
                while (guard++ < 400) {
                    val c = _chat.value
                    if (c.sessionId != sid || !c.hasMore) break
                    val before = c.oldestLoadedId ?: break
                    val page = api.messagesPage(sid, before, limit = 500)
                    if (_chat.value.sessionId != sid) return@launch
                    val merged = mergeMessageLists(_chat.value.messages, page.messages)
                    _chat.value = _chat.value.copy(
                        messages = merged,
                        hasMore = page.hasMore,
                        oldestLoadedId = page.oldest,
                    )
                    if (page.messages.isEmpty()) break
                }
                persistCache(sid)
            } catch (e: Exception) {
                /* keep what was already merged */
            } finally {
                _chat.value = _chat.value.copy(loadingMore = false)
            }
        }
    }

    /** Messages are append-only, so merging by id is safe: `incoming` wins. */
    private fun mergeMessageLists(
        base: List<MessageWithParts>,
        incoming: List<MessageWithParts>,
    ): List<MessageWithParts> {
        if (base.isEmpty()) return sortMessages(incoming)
        if (incoming.isEmpty()) return sortMessages(base)
        val map = LinkedHashMap<String, MessageWithParts>()
        base.forEach { map[it.info.id] = it }
        incoming.forEach { n ->
            val prev = map[n.info.id]
            map[n.info.id] = if (prev != null) mergeMessages(prev, n) else n
        }
        return sortMessages(map.values.toList())
    }

    /** Old caches persisted optimistic local- bubbles — they must never resurface (they duplicated the real echo). */
    private fun kanami.isa.opencode.connect.data.CachedChat.dropLocalEchoes(): kanami.isa.opencode.connect.data.CachedChat =
        copy(messages = messages.filterNot { it.info.id.startsWith("local-") })

    private fun persistCache(sessionId: String) {
        val cur = _chat.value
        if (cur.sessionId != sessionId || cur.messages.isEmpty()) return
        val real = cur.messages.filterNot { it.info.id.startsWith("local-") }
        if (real.isEmpty()) return
        val tail = real.takeLast(CACHE_TAIL)
        val cached = CachedChat(
            messages = tail,
            oldest = tail.first().info.id,
            hasMore = cur.hasMore,
            at = System.currentTimeMillis(),
        )
        memCache[sessionId] = cached
        viewModelScope.launch { runCatching { cacheStore.save(sessionId, cached) } }
    }

    fun setDraft(text: String) {
        val sid = _chat.value.sessionId ?: return
        _chat.value = _chat.value.copy(draft = text)
        val gen = ++draftGeneration
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(400)
            draftMutex.withLock {
                if (gen != draftGeneration) return@withLock
                runCatching { settings.setDraft(sid, text) }
            }
        }
    }

    private fun clearDraftPersisted(sessionId: String) {
        draftGeneration++
        draftJob?.cancel()
        viewModelScope.launch {
            draftMutex.withLock {
                runCatching { settings.setDraft(sessionId, "") }
            }
        }
    }

    fun closeChat() {
        val sid = _chat.value.sessionId
        if (sid != null) persistCache(sid)
        // No poll cancellation: the session may still be mid-turn — its poll
        // keeps running in background mode and drops busy when the turn ends.
        pendingLocalId = null
        _chat.value = ChatUiState()
    }

    private var pendingLocalId: String? = null
    private var promptJob: kotlinx.coroutines.Job? = null
    private var promptSentAt = 0L
    private var promptDraft: String? = null

    /** opencode admitted the input (204 from prompt_async) — delivered, maybe not started. */
    private var promptAccepted = false

    fun sendPrompt(text: String, attachments: List<FileAttachment> = emptyList()) {
        val sessionId = _chat.value.sessionId ?: return
        if (text.isBlank() && attachments.isEmpty()) return
        val draftBefore = _chat.value.draft
        clearDraftPersisted(sessionId)
        setBusy(sessionId, true)
        _chat.value = _chat.value.copy(draft = "", error = null, retryPrompt = null)
        startTailPolling(sessionId)
        val prevLocal = pendingLocalId
        val cleaned = if (prevLocal != null) _chat.value.messages.filterNot { it.info.id == prevLocal } else _chat.value.messages
        val localId = "local-${System.currentTimeMillis()}"
        val time: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
            put("created", kotlinx.serialization.json.JsonPrimitive(System.currentTimeMillis().toString()))
        }
        val localParts = buildList {
            add(Part(id = "$localId-0", sessionID = sessionId, messageID = localId, type = "text", text = text))
            attachments.forEachIndexed { i, f ->
                add(
                    Part(
                        id = "$localId-${i + 1}",
                        sessionID = sessionId,
                        messageID = localId,
                        type = "file",
                        mime = f.mime,
                        filename = f.name,
                        url = f.url,
                    )
                )
            }
        }
        val localMsg = MessageWithParts(
            info = MessageInfo(id = localId, sessionID = sessionId, role = "user", time = time),
            parts = localParts,
        )
        pendingLocalId = localId
        _chat.value = _chat.value.copy(
            messages = sortMessages(cleaned + localMsg),
            awaitingUpstream = true,
            pendingUserMsgId = localId,
        )
        promptSentAt = System.currentTimeMillis()
        promptDraft = draftBefore
        promptAccepted = false
        promptJob = viewModelScope.launch {
            try {
                deliverPrompt(sessionId, text, attachments)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // failSend()/shutdown cancelled us on purpose: it already owns the
                // state, never overwrite it with a coroutine-cancel message.
                throw e
            } catch (e: Exception) {
                val stillRunning = runCatching {
                    val st = api.sessionStatus()
                    val s = (st[sessionId] as? JsonObject)?.str("type")
                    s == "busy" || s == "retry"
                }.getOrDefault(false)
                if (stillRunning) {
                    // The turn is alive, only the waiting POST died. Keep the state:
                    // SSE streams the answer and the status poll closes it.
                    promptSentAt = 0L
                    promptDraft = null
                } else {
                    failSend(sessionId, e.message ?: "send failed")
                }
            }
        }
    }

    /**
     * Sends through `prompt_async` (204 in ~0.3s; the turn starts on its own) and only
     * falls back to the blocking `POST /message` when a server without that endpoint
     * answers 404/405. On the async path `promptSentAt` deliberately stays armed so the
     * status-poll watchdog can catch "admitted, but the reply never started".
     */
    private suspend fun deliverPrompt(
        sessionId: String,
        text: String,
        attachments: List<FileAttachment>,
    ) {
        val agent = _selectedAgent.value.ifEmpty { null }
        try {
            api.promptAsync(sessionId, text, agent, attachments)
            promptAccepted = true
            return
        } catch (e: ApiException) {
            if (e.status != 404 && e.status != 405) throw e
        }
        val response = api.prompt(sessionId, text, agent, attachments)
        runCatching { settings.setDraft(sessionId, "") }
        promptSentAt = 0L
        promptDraft = null
        setBusy(sessionId, false)
        if (response != null) {
            upsertMessage(response)
        }
    }

    /** The server echoed our input back: delivered for sure — disarm the watchdog. */
    private fun markDelivered() {
        promptSentAt = 0L
        promptDraft = null
        promptAccepted = false
    }

    /**
     * opencode accepted the POST but never started a turn (seen in the wild: the
     * request hung, no `stream`/`process` log lines, status stayed idle). Give up
     * instead of shimming forever, and hand the draft back so it can be resent.
     */
    private fun failSend(sessionId: String, message: String) {
        val draft = promptDraft
        promptDraft = null
        promptSentAt = 0L
        promptAccepted = false
        promptJob?.cancel()
        promptJob = null
        tailPollJobs[sessionId]?.cancel()
        tailPollJobs.remove(sessionId)
        setBusy(sessionId, false)
        if (_chat.value.sessionId == sessionId) {
            _chat.value = _chat.value.copy(
                error = friendlySendError(message),
                draft = draft ?: _chat.value.draft,
            )
        }
        if (draft != null) {
            viewModelScope.launch { runCatching { settings.setDraft(sessionId, draft) } }
        }
    }

    /**
     * The turn ended (or died) with nothing to show. Surface a real error with a
     * Retry action instead of leaving the user staring at an empty bubble — the
     * draft is NOT returned to the input: the message is already in the store.
     */
    private fun failTurn(sessionId: String, message: String) {
        val retry = _chat.value.messages.lastOrNull { it.info.role == "user" }
            ?.parts?.firstOrNull { it.type == "text" }?.text
        setBusy(sessionId, false)
        if (_chat.value.sessionId == sessionId) {
            _chat.value = _chat.value.copy(
                error = message,
                retryPrompt = retry ?: _chat.value.retryPrompt,
            )
        }
    }

    /** Non-null when a finished turn left no usable output at all. */
    private fun deadTurnReason(msgs: List<MessageWithParts>): String? {
        val last = msgs.lastOrNull() ?: return null
        if (last.info.role != "assistant") return null
        // message-level errors already render their own ErrorCard
        if (last.info.error != null) return null
        val text = last.parts.filter { it.type == "text" }.joinToString("") { it.text.orEmpty() }.trim()
        val hasOutput = last.parts.any { it.type == "tool" || it.type == "patch" || it.type == "file" }
        if (text.isNotEmpty() || hasOutput) return null
        return "The model returned no reply (provider error, rate limit or empty response). " +
            "Tap Retry to send your message again."
    }

    private fun hasRunningParts(msgs: List<MessageWithParts>): Boolean = msgs.any { m ->
        m.parts.any { p ->
            (p.state?.get("status") as? JsonPrimitive)?.content.let { it == "running" || it == "pending" }
        }
    }

    /** Re-send the message of the turn that failed; entry point for the Retry button. */
    fun retryTurnFromError() {
        val text = _chat.value.retryPrompt ?: return
        _chat.value = _chat.value.copy(error = null, retryPrompt = null)
        sendPrompt(text)
    }

    /**
     * Two classes of errors are noise, not feedback: the "not connected" race (a
     * background poller beating the websocket handshake — the Connect screen already
     * shows the state) and coroutine/server cancellation texts the user can't act on.
     */
    private fun toastError(prefix: String, e: Throwable) {
        val raw = e.message.orEmpty()
        if (raw.contains("not connected") || raw.contains("was cancelled")) return
        _toast.tryEmit("$prefix: $raw")
    }

    /** Raw server / coroutine cancellation texts -> something the user can act on. */
    private fun friendlySendError(raw: String?): String {
        val m = raw.orEmpty()
        if (m.contains("was cancelled", ignoreCase = true) ||
            m.contains("is cancelled", ignoreCase = true)
        ) {
            return "opencode cancelled the request before the reply started. " +
                "Your text is back in the input — send it again."
        }
        return if (m.isBlank()) "send failed" else m
    }

    fun abortPrompt() {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            try {
                api.abort(sessionId)
                setBusy(sessionId, false)
            } catch (e: Exception) {
                toastError("Abort", e)
            }
        }
    }

    fun clearChatError() {
        _chat.value = _chat.value.copy(error = null)
    }

    private fun sortMessages(list: List<MessageWithParts>): List<MessageWithParts> =
        list.sortedBy { it.info.time?.let { t -> (t["created"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L } ?: 0L }

    private fun upsertMessage(msg: MessageWithParts) {
        val cur = _chat.value
        if (cur.sessionId == null || msg.info.sessionID != cur.sessionId) return
        val idx = cur.messages.indexOfFirst { it.info.id == msg.info.id }
        val newList = if (idx >= 0) {
            cur.messages.toMutableList().apply { set(idx, mergeMessages(this[idx], msg)) }
        } else {
            sortMessages(cur.messages + msg)
        }
        _chat.value = cur.copy(messages = newList)
    }

    private fun mergeMessages(old: MessageWithParts, new: MessageWithParts): MessageWithParts {
        if (old.parts.isEmpty()) return new
        val partMap = LinkedHashMap<String, Part>()
        old.parts.forEach { partMap[it.id] = it }
        new.parts.forEach { np ->
            val prev = partMap[np.id]
            partMap[np.id] = if (
                prev != null && new.truncated && np.text != null && prev.text != null &&
                prev.text.length > np.text.length
            ) {
                // The tail endpoint cuts the START of a part we already hold in
                // full — keep the longer copy, take the fresh state/time.
                np.copy(text = prev.text, state = np.state ?: prev.state, time = np.time ?: prev.time)
            } else {
                np
            }
        }
        // A stale cached tail snapshot may lack the completion we already saw.
        val oldDone = old.info.time?.get("completed") != null
        val newDone = new.info.time?.get("completed") != null
        val info = if (oldDone && !newDone) old.info else new.info
        return MessageWithParts(
            info = info,
            parts = partMap.values.toList(),
            truncated = old.truncated && new.truncated,
            fullChars = maxOf(old.fullChars, new.fullChars),
        )
    }

    private fun updatePart(part: Part) {
        val cur = _chat.value
        if (cur.sessionId == null) return
        if (part.sessionID.isEmpty() || part.sessionID != cur.sessionId) return
        if (!part.id.startsWith("local-") && cur.awaitingUpstream) {
            markDelivered()
            // awaitingUpstream deliberately survives the user-message echo: it now
            // means "my message is in, no reply yet" and keeps the processing bubble
            // alive for turns the desktop host runs (serve never lists those as busy).
        }
        val msgIdx = cur.messages.indexOfFirst { it.info.id == part.messageID }
        if (msgIdx < 0) {
            // unknown message: create a shell so streaming still shows
            val shell = MessageWithParts(
                info = MessageInfo(id = part.messageID, sessionID = part.sessionID, role = "assistant"),
                parts = listOf(part),
            )
            _chat.value = cur.copy(messages = sortMessages(cur.messages + shell))
            return
        }
        val msg = cur.messages[msgIdx]
        val pIdx = msg.parts.indexOfFirst { it.id == part.id }
        val appended = if (pIdx >= 0) {
            msg.parts.toMutableList().apply { set(pIdx, part) }
        } else {
            msg.parts + part
        }
        val newParts = if (appended.any { it.id.startsWith("local-") } && !part.id.startsWith("local-")) {
            appended.filterNot { it.id.startsWith("local-") }
        } else {
            appended
        }
        val newList = cur.messages.toMutableList().apply {
            set(msgIdx, MessageWithParts(msg.info, newParts))
        }
        _chat.value = cur.copy(messages = newList)
    }

    private fun removePart(sessionId: String, messageId: String, partId: String) {
        val cur = _chat.value
        if (cur.sessionId != sessionId) return
        val msgIdx = cur.messages.indexOfFirst { it.info.id == messageId }
        if (msgIdx < 0) return
        val msg = cur.messages[msgIdx]
        val newList = cur.messages.toMutableList().apply {
            set(msgIdx, MessageWithParts(msg.info, msg.parts.filterNot { it.id == partId }))
        }
        _chat.value = cur.copy(messages = newList)
    }

    private fun updateMessageInfo(info: MessageInfo) {
        val cur = _chat.value
        if (cur.sessionId == null || info.sessionID != cur.sessionId) return
        if (info.role == "assistant" && cur.awaitingUpstream) {
            markDelivered()
            _chat.value = cur.copy(awaitingUpstream = false)
        }
        val idx = cur.messages.indexOfFirst { it.info.id == info.id }
        if (idx < 0) {
            val pending = pendingLocalId
            if (pending != null && info.id != pending && info.role == "user") {
                pendingLocalId = null
                _chat.value = cur.copy(
                    messages = cur.messages.map {
                        if (it.info.id == pending) MessageWithParts(info, it.parts) else it
                    },
                )
                return
            }
            _chat.value = cur.copy(messages = sortMessages(cur.messages + MessageWithParts(info, emptyList())))
            return
        }
        val old = cur.messages[idx]
        val merged = MessageWithParts(
            info = info,
            parts = old.parts,
        )
        val newList = cur.messages.toMutableList().apply { set(idx, merged) }
        _chat.value = cur.copy(messages = newList)
    }

    private fun refreshBusyFromServer() {
        val sid = _chat.value.sessionId ?: return
        viewModelScope.launch {
            runCatching {
                val st = api.sessionStatus()
                val s = (st[sid] as? JsonObject)?.str("type")
                if (s != null) setBusy(sid, s == "busy" || s == "retry")
            }
        }
    }

    private fun scrollToBottomIfNeeded() {}

    private companion object {
        const val CACHE_TAIL = 40

        /** No upstream tokens and opencode not busy after this => send never started. */
        const val PROMPT_SILENCE_MS = 20_000L

        /** prompt_async admitted the input (204) — the PC session may queue the turn. */
        const val PROMPT_ACCEPTED_SILENCE_MS = 60_000L

        /**
         * Host-run (`opencode run` on the PC) turns emit no SSE and never appear in the
         * status map — the message store is the only place they show up. Poll the tail
         * while a turn is on so the phone sees the text and knows when it ends.
         */
        const val TAIL_POLL_MS = 1600L

        /** Chars of history per tail poll: enough for the live turn, small payload. */
        const val TAIL_POLL_CHARS = 16384

        /** Completion must hold for this many consecutive polls before busy drops. */
        const val TAIL_DONE_STREAK = 3

        /** Silent store with no running parts for this long => the turn died. */
        const val TURN_STALL_MS = 120_000L

        /** Consecutive tail failures (~16s) before the poll gives up. */
        const val TAIL_MAX_ERRORS = 10
    }

    // --- events ---

    private fun handleEvent(ev: JsonObject) {
        val type = (ev["type"] as? JsonPrimitive)?.contentOrNull ?: return
        val props = ev["properties"] as? JsonObject
        val data = ev["data"] as? JsonObject
        val payload = props ?: data ?: return
        when (type) {
            "message.updated" -> {
                val info = payload["info"] as? JsonObject ?: return
                runCatching {
                    val mi = json.decodeFromJsonElement<MessageInfo>(info)
                    viewModelScope.launch {
                        trackTurnSignal(mi)
                        updateMessageInfo(mi)
                    }
                }
            }
            "message.part.updated" -> {
                val part = payload["part"] as? JsonObject ?: return
                runCatching {
                    val p = json.decodeFromJsonElement<Part>(part)
                    viewModelScope.launch { updatePart(p) }
                }
            }
            "message.part.removed" -> {
                val sid = payload.str("sessionID") ?: return
                val mid = payload.str("messageID") ?: return
                val pid = payload.str("partID") ?: return
                viewModelScope.launch { removePart(sid, mid, pid) }
            }
            "session.updated" -> {
                val info = payload["info"] as? JsonObject ?: return
                runCatching {
                    val s = json.decodeFromJsonElement<Session>(info)
                    viewModelScope.launch { onSessionUpdated(s) }
                }
            }
            "session.created" -> {
                val info = payload["info"] as? JsonObject ?: return
                runCatching {
                    val s = json.decodeFromJsonElement<Session>(info)
                    viewModelScope.launch {
                        if (_sessions.value.none { it.id == s.id }) {
                            _sessions.value = listOf(s) + _sessions.value
                        }
                    }
                }
            }
            "session.deleted" -> {
                val sid = payload.str("sessionID") ?: payload.str("id") ?: return
                viewModelScope.launch {
                    _sessions.value = _sessions.value.filterNot { it.id == sid }
                    if (_chat.value.sessionId == sid) closeChat()
                }
            }
            "session.status" -> {
                val sid = payload.str("sessionID") ?: return
                val status = payload["status"] as? JsonObject
                val statusType = status?.str("type")
                viewModelScope.launch {
                    if (statusType == "busy" || statusType == "retry") {
                        setBusy(sid, true)
                    } else if (tailPollJobs[sid]?.isActive != true) {
                        setBusy(sid, false)
                    }
                    // else: the tail poll owns the busy flag and clears it itself on
                    // a finished turn — a stray "idle" (split-brain, host-run turn)
                    // must not kill live polling mid-generation.
                }
            }
            "session.error" -> {
                val sid = payload.str("sessionID") ?: return
                val msg = errorText(payload)
                viewModelScope.launch {
                    setBusy(sid, false)
                    if (_chat.value.sessionId == sid) {
                        _chat.value = _chat.value.copy(error = msg)
                    }
                }
            }
            "permission.asked" -> {
                runCatching {
                    val req = PermissionRequest(
                        id = payload.str("id") ?: return@runCatching,
                        sessionID = payload.str("sessionID") ?: "",
                        permission = payload.str("permission") ?: "",
                        patterns = (payload["patterns"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                        metadata = payload["metadata"] as? JsonObject,
                        always = (payload["always"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                    )
                    _permission.value = req
                }
            }
            "permission.replied" -> _permission.value = null
            "question.asked" -> {
                runCatching {
                    val id = payload.str("id") ?: return@runCatching
                    val sid = payload.str("sessionID") ?: ""
                    val qs = (payload["questions"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull { it as? JsonObject } ?: emptyList()
                    if (id !in handledQuestionIds) {
                        _question.value = QuestionRequest(id, sid, qs)
                        questionSetAt = System.currentTimeMillis()
                        if (!appInForeground) notifyQuestion(sid, qs)
                    }
                }
            }
            "question.replied", "question.rejected" -> _question.value = null
        }
    }

    private fun notifyQuestion(sid: String, questions: List<JsonObject>) {
        val text = questions.firstOrNull()?.str("question")
            ?: _question.value?.questions?.firstOrNull()?.str("question")
            ?: "OpenCode is waiting for an answer"
        val title = _sessions.value.find { it.id == sid }?.title
            ?: _chat.value.title
            ?: "OpenCode"
        Notifier.notifyQuestion(getApplication(), title, text, sid)
    }

    /** Questions can outlive a restart (the agent keeps waiting), so re-read them on connect. */
    fun refreshPendingQuestions() {
        viewModelScope.launch {
            runCatching {
                val list = api.pendingQuestions()
                val cur = _question.value
                // answered/rejected on the PC (or by another device): the queue
                // is the truth. The age guard skips the SSE-event race right
                // after question.asked, before the queue catches up.
                if (cur != null && cur.id !in list.map { it.id } &&
                    System.currentTimeMillis() - questionSetAt > 10_000L
                ) {
                    handledQuestionIds.add(cur.id)
                    _question.value = null
                }
                if (_question.value != null) return@runCatching
                val next = list.firstOrNull { it.id !in handledQuestionIds } ?: return@runCatching
                _question.value = next
                questionSetAt = System.currentTimeMillis()
                if (!appInForeground) notifyQuestion(next.sessionID, next.questions)
            }
        }
    }

    fun setShowProjects(value: Boolean) {
        _showProjects.value = value
        viewModelScope.launch { runCatching { settings.saveProjectsMode(value) } }
    }

    private fun onSessionUpdated(s: Session) {
        _sessions.value = _sessions.value
            .map { if (it.id == s.id) s else it }
            .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
        if (_chat.value.sessionId == s.id) {
            _chat.value = _chat.value.copy(
                title = s.title ?: _chat.value.title,
                model = s.model ?: _chat.value.model,
            )
        }
    }

    fun answerPermission(reply: String, message: String? = null) {
        val req = _permission.value ?: return
        viewModelScope.launch {
            try {
                api.replyPermission(req.id, reply, message)
                _permission.value = null
            } catch (e: Exception) {
                toastError("Permission reply", e)
                _permission.value = null
            }
        }
    }

    fun answerQuestion(answers: List<List<String>>) {
        val req = _question.value ?: return
        handledQuestionIds.add(req.id)
        viewModelScope.launch {
            try {
                api.replyQuestion(req.sessionID, req.id, answers)
                _question.value = null
                refreshPendingQuestions()
            } catch (e: Exception) {
                toastError("Question reply", e)
                _question.value = null
                refreshPendingQuestions()
            }
        }
    }

    fun rejectQuestion() {
        val req = _question.value ?: return
        handledQuestionIds.add(req.id)
        viewModelScope.launch {
            try {
                api.rejectQuestion(req.sessionID, req.id)
            } catch (_: Exception) {
            } finally {
                _question.value = null
                refreshPendingQuestions()
            }
        }
    }

    // --- meta ---

    fun loadMeta() {
        viewModelScope.launch {
            runCatching { _providers.value = api.providers() }
                .onFailure { toastError("Providers", it) }
            runCatching {
                val list = api.agents().filter { it.hidden != true }
                _agents.value = list
            }
        }
    }

    fun selectSessionModel(providerID: String, modelID: String) {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            try {
                api.switchModel(sessionId, providerID, modelID)
                val ref = ModelRef(providerID = providerID, id = modelID, modelID = modelID)
                if (_chat.value.sessionId == sessionId) {
                    _chat.value = _chat.value.copy(model = ref)
                }
                _sessions.value = _sessions.value.map {
                    if (it.id == sessionId) it.copy(model = ref) else it
                }
            } catch (e: Exception) {
                toastError("Model switch", e)
            }
        }
    }

    fun selectAgent(agent: String) {
        _selectedAgent.value = agent
        viewModelScope.launch { settings.saveAgent(agent) }
    }

    // --- files ---

    fun openFiles(rootPath: String) {
        _files.value = FilesUiState(path = rootPath, loading = true)
        viewModelScope.launch {
            val saved = runCatching { settings.filesSort() }.getOrDefault("name")
            _files.value = _files.value.copy(sort = saved)
            loadDir(rootPath)
        }
    }

    fun loadDir(path: String) {
        viewModelScope.launch {
            _files.value = _files.value.copy(path = path, loading = true, error = null)
            try {
                val nodes = api.files(path)
                _files.value = _files.value.copy(
                    path = path,
                    nodes = sortFileNodes(nodes, _files.value.sort),
                    loading = false,
                )
            } catch (e: Exception) {
                _files.value = _files.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun setFilesSort(mode: String) {
        val cur = _files.value
        _files.value = cur.copy(sort = mode, nodes = sortFileNodes(cur.nodes, mode))
        viewModelScope.launch { settings.saveFilesSort(mode) }
    }

    private fun sortFileNodes(nodes: List<FileNode>, sort: String): List<FileNode> {
        val primary: Comparator<FileNode> = when (sort) {
            "name_desc" -> compareByDescending { it.name.lowercase() }
            "size" -> compareBy { it.size }
            "size_desc" -> compareByDescending { it.size }
            "date" -> compareBy { it.modified }
            "date_desc" -> compareByDescending { it.modified }
            else -> compareBy { it.name.lowercase() }
        }
        // directories stay on top regardless of the chosen order
        return nodes.sortedWith(compareBy<FileNode> { it.type != "directory" }.then(primary))
    }

    fun goUpDir() {
        val cur = _files.value.path
        // NOT java.io.File: on Android it splits on '/', so for the agent's
        // "C:\..." paths getParent() is null and going up silently did nothing.
        val parent = pcParent(cur)?.takeIf { it != cur } ?: return
        loadDir(parent)
    }

    fun openFile(path: String) {
        viewModelScope.launch {
            try {
                val fc: FileContent = api.fileContent(path)
                _viewer.value = ViewerState(
                    path = path,
                    content = fc.content ?: "",
                    binary = fc.type == "binary",
                )
            } catch (e: Exception) {
                toastError("File", e)
            }
        }
    }

    fun closeViewer() {
        _viewer.value = null
    }

    private fun absOf(node: FileNode): String =
        if (node.absolute.isNotEmpty()) node.absolute
        else pcJoin(node.path, node.name)

    fun startPendingOp(kind: String, node: FileNode) {
        _pendingOp.value = PendingOp(kind, listOf(absOf(node)), node.name)
        toast(if (kind == "copy") "Copy — pick a folder, then Copy here" else "Move — pick a folder, then Move here")
    }

    fun startBatchPendingOp(kind: String, paths: List<String>, label: String) {
        if (paths.isEmpty()) return
        _pendingOp.value = PendingOp(kind, paths, label)
        toast(if (kind == "copy") "Copy — pick a folder, then Copy here" else "Move — pick a folder, then Move here")
    }

    fun cancelPendingOp() {
        _pendingOp.value = null
    }

    /** Paste the held copy/move into the directory the browser shows right now. */
    fun pasteHere() {
        val op = _pendingOp.value ?: return
        val dir = _files.value.path
        if (dir.isEmpty()) return
        runFileOp(if (op.kind == "copy") "Copied" else "Moved") {
            for (src in op.paths) {
                val base = src.substringAfterLast('\\').substringAfterLast('/')
                val to = pcJoin(dir, base)
                if (op.kind == "copy") api.copyFile(src, to) else api.moveFile(src, to)
            }
            _pendingOp.value = null
        }
    }

    fun renameFile(node: FileNode, newName: String) {
        val from = absOf(node)
        val parent = pcParent(from)
        if (parent == null) {
            toast("Rename: cannot resolve the parent directory")
            return
        }
        runFileOp("Renamed") { api.moveFile(from, pcJoin(parent, newName)) }
    }

    /** rm — the agent only ever moves the target into the Windows recycle bin. */
    fun deleteFile(node: FileNode) {
        runFileOp("Deleted to recycle bin") { api.deleteFile(absOf(node)) }
    }

    /** Batch delete: everything into the Windows recycle bin, one by one. */
    fun batchDelete(paths: List<String>) {
        if (paths.isEmpty()) return
        runFileOp("Deleted to recycle bin") {
            for (p in paths) api.deleteFile(p)
        }
    }

    /** Batch zip: password-less archive created next to the selection. */
    fun batchZip(paths: List<String>) {
        if (paths.isEmpty()) return
        val dir = _files.value.path
        if (dir.isEmpty()) return
        val name = "archive-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".zip"
        runFileOp("Archive created: $name") {
            api.fileZip(paths, pcJoin(dir, name))
        }
    }

    /** Upload a picked phone file into the folder the browser shows right now. */
    fun uploadToCurrent(uri: android.net.Uri) {
        val dir = _files.value.path
        if (dir.isEmpty()) return
        runFileOp("Uploaded") {
            val cr = getApplication<Application>().contentResolver
            val name = cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
            val bytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cr.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("cannot read the picked file")
            }
            api.uploadFile(pcJoin(dir, name), bytes)
        }
    }

    /** mkdir in the folder the browser shows right now (file screen and the picker share this). */
    fun createFolder(name: String) {
        val dir = _files.value.path
        val clean = name.trim()
        if (dir.isEmpty() || clean.isEmpty()) return
        runFileOp("Folder created") { api.createFolder(pcJoin(dir, clean)) }
    }

    private fun runFileOp(success: String, block: suspend () -> Unit) {
        if (_fileOpBusy.value) return
        viewModelScope.launch {
            _fileOpBusy.value = true
            try {
                block()
                toast(success)
                loadDir(_files.value.path)
            } catch (e: Exception) {
                toastError("File op", e)
            } finally {
                _fileOpBusy.value = false
            }
        }
    }

    /** Streams the file off the PC in base64 chunks into the SAF document the picker created (Android 8-9). */
    fun downloadFile(node: FileNode, uri: android.net.Uri) = startDownload(node, uri)

    /**
     * Android 10+: save straight into Download/OpenCodeConnect without asking —
     * MediaStore lets an app insert its own files into the public Downloads
     * collection with no permission and no picker.
     */
    fun downloadFile(node: FileNode) = startDownload(node, null)

    private fun startDownload(node: FileNode, uriOrNull: android.net.Uri?) {
        downloadJob?.cancel()
        val abs = absOf(node)
        val name = node.name
        _download.value = DownloadUiState(name = name, running = true)
        downloadJob = viewModelScope.launch {
            val cr = getApplication<Application>().contentResolver
            var uri: android.net.Uri? = uriOrNull
            try {
                if (uri == null) {
                    val finalName = uniqueDownloadName(cr, name)
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, guessMime(finalName))
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/OpenCodeConnect",
                        )
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    uri = cr.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: throw java.io.IOException("cannot create the file in Download/OpenCodeConnect")
                }
                val dest = uri ?: throw java.io.IOException("cannot create the destination file")
                val out = cr.openOutputStream(dest)
                    ?: throw java.io.IOException("cannot open the destination")
                out.use { stream ->
                    var offset = 0L
                    while (true) {
                        val chunk = api.fileChunk(abs, offset)
                        val bytes = android.util.Base64.decode(chunk.data, android.util.Base64.DEFAULT)
                        if (bytes.isNotEmpty()) stream.write(bytes)
                        offset += bytes.size
                        _download.value = _download.value?.copy(total = chunk.total, done = offset)
                        if (chunk.eof || bytes.isEmpty()) break
                    }
                    stream.flush()
                }
                if (uriOrNull == null) {
                    // publish the pending MediaStore row so other apps can see it
                    val publish = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    cr.update(dest, publish, null, null)
                    _download.value = null
                    _downloadDone.value = DownloadDone(name = name, uri = dest, mime = guessMime(name))
                } else {
                    _download.value = null
                    _downloadDone.value = DownloadDone(name = name, uri = dest, mime = guessMime(name))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancelled mid-flight: drop the partial file we created
                if (uri != null) runCatching { cr.delete(uri, null, null) }
                _download.value = null
                throw e
            } catch (e: Exception) {
                if (uri != null) runCatching { cr.delete(uri, null, null) }
                _download.value = DownloadUiState(name = name, running = false, error = e.message)
                toastError("Download", e)
            }
        }
    }

    /** Appends " (1)", " (2)"… instead of silently colliding with an existing download. */
    private fun uniqueDownloadName(cr: android.content.ContentResolver, base: String): String {
        val dir = android.os.Environment.DIRECTORY_DOWNLOADS + "/OpenCodeConnect/"
        val existing = mutableSetOf<String>()
        runCatching {
            cr.query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                arrayOf(dir),
                null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(0)?.lowercase()?.let { existing.add(it) }
            }
        }
        if (base.lowercase() !in existing) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var i = 1
        while ("$stem ($i)$ext".lowercase() in existing) i++
        return "$stem ($i)$ext"
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun dismissDownload() {
        _download.value = null
    }

    fun dismissDownloadDone() {
        _downloadDone.value = null
    }

    /** Working directory of the session currently open in the chat. */
    fun projectDirectory(): String {
        val sid = _chat.value.sessionId ?: return ""
        return sessions.value.find { it.id == sid }?.directory ?: ""
    }

    override fun onCleared() {
        eventJob?.cancel()
        sessionStatusJob?.cancel()
        tailPollJobs.values.forEach { it.cancel() }
        tailPollJobs.clear()
        relay.disconnect()
        super.onCleared()
    }

    /**
     * Poll `messages-tail` while a turn is on. Host-run turns (the operator's
     * `opencode run` on the PC) never emit SSE and never show in
     * `GET /session/status` — the message store is the only thing that moves.
     * 1.6s ≈ human-perceptible streaming without hammering the relay; 16384
     * chars covers the live tail of a long reply.
     */
    private fun startTailPolling(sessionId: String) {
        if (tailPollJobs[sessionId]?.isActive == true) return
        tailPollJobs[sessionId] = viewModelScope.launch {
            var errors = 0
            var doneStreak = 0
            var lastSig = -1
            var lastChangeAt = System.currentTimeMillis()
            while (true) {
                delay(TAIL_POLL_MS)
                // The owner is the per-session busy flag, not the visible chat:
                // polls outlive session switches and keep tracking in background.
                if (busyBySession[sessionId] != true) {
                    dbg("tail stop $sessionId: busy flag cleared")
                    break
                }
                val page = try {
                    api.messagesTail(sessionId, TAIL_POLL_CHARS)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // hiccups are normal (relay reconnects); give up on a dead streak
                    if (++errors >= TAIL_MAX_ERRORS) {
                        dbg("tail stop $sessionId: $errors consecutive errors (${e.message})")
                        failTurn(
                            sessionId,
                            "Connection to the PC agent was lost — the reply may never arrive. " +
                                "Tap Retry to send your message again.",
                        )
                        break
                    }
                    continue
                }
                errors = 0
                // the fetch can outlast a session switch — never merge into a foreign chat
                val active = _chat.value.sessionId == sessionId
                if (active) {
                    var merged = mergeMessageLists(_chat.value.messages, page.messages)
                    // The optimistic bubble is stale once the store shows the real message.
                    val pending = pendingLocalId
                    if (pending != null) {
                        val pendingText = merged.firstOrNull { it.info.id == pending }
                            ?.parts?.firstOrNull { it.type == "text" }?.text
                        val realEcho = pendingText != null && merged.any { m ->
                            m.info.role == "user" && !m.info.id.startsWith("local-") &&
                                m.parts.any { it.type == "text" && it.text == pendingText }
                        }
                        if (realEcho) {
                            pendingLocalId = null
                            merged = merged.filterNot { it.info.id == pending }
                            // the server echoed our input back — delivered for sure
                            markDelivered()
                        }
                    }
                    // The reply is on screen: disarm the 60s send-watchdog before it
                    // falsely reports "not delivered" in the middle of a host-run turn.
                    if (_chat.value.awaitingUpstream && merged.lastOrNull()?.info?.role == "assistant") {
                        markDelivered()
                    }
                    if (merged != _chat.value.messages) {
                        _chat.value = _chat.value.copy(messages = merged)
                        persistCache(sessionId)
                    }
                    // A turn that stopped growing without finishing is dead (an
                    // upstream stream error killed it): say so instead of spinning
                    // the processing bubble forever.
                    val sig = merged.size + merged.takeLast(2)
                        .sumOf { m -> m.parts.sumOf { (it.text?.length ?: 0) + it.type.length } }
                    val nowMs = System.currentTimeMillis()
                    if (sig != lastSig) {
                        lastSig = sig
                        lastChangeAt = nowMs
                    } else if (
                        nowMs - lastChangeAt > TURN_STALL_MS && !page.inFlight && !hasRunningParts(merged)
                    ) {
                        dbg("tail stall $sessionId: no growth for ${nowMs - lastChangeAt}ms")
                        failTurn(
                            sessionId,
                            "The reply stalled without finishing (provider error or rate limit). " +
                                "Tap Retry to send your message again.",
                        )
                        break
                    }
                    // The agent knows the turn is still running (fresh SSE activity /
                    // pending ff-block) even while the store looks finished.
                    if (turnFinished(merged) && !page.inFlight) {
                        if (++doneStreak >= TAIL_DONE_STREAK) {
                            markDelivered()
                            setBusy(sessionId, false)
                            deadTurnReason(merged)?.let { failTurn(sessionId, it) }
                            persistCache(sessionId)
                            dbg("tail stop $sessionId: turn finished")
                            break
                        }
                    } else {
                        doneStreak = 0
                    }
                } else {
                    // Background session: only watch for the end of the turn.
                    if (turnFinished(page.messages) && !page.inFlight) {
                        if (++doneStreak >= TAIL_DONE_STREAK) {
                            setBusy(sessionId, false)
                            dbg("tail stop $sessionId: turn finished (bg)")
                            break
                        }
                    } else {
                        doneStreak = 0
                    }
                }
            }
            tailPollJobs.remove(sessionId)
        }
    }

    /** Last assistant message is completed and no tool call is still running. */
    private fun turnFinished(msgs: List<MessageWithParts>): Boolean {
        val last = msgs.lastOrNull() ?: return false
        if (last.info.role != "assistant") return false
        if (last.info.error != null) return true
        if (last.info.time?.get("completed") == null) return false
        // "tool-calls" = another step of the same turn is queued — not done yet.
        if (last.info.finish == "tool-calls") return false
        return last.parts.none { p ->
            (p.state?.get("status") as? JsonPrimitive)?.content.let { it == "running" || it == "pending" }
        }
    }

    /** Opened a session that is mid-turn right now (host-run turn or live reply). */
    private fun lastTurnInFlight(msgs: List<MessageWithParts>): Boolean {
        val last = msgs.lastOrNull() ?: return false
        if (last.info.role != "assistant") return false
        if (last.info.error != null) return false
        if (last.info.time?.get("completed") == null) return true
        if (last.info.finish == "tool-calls") return true
        return last.parts.any { p ->
            (p.state?.get("status") as? JsonPrimitive)?.content.let { it == "running" || it == "pending" }
        }
    }

    private fun startStatusPolling() {
        if (sessionStatusJob != null) return
        sessionStatusJob = viewModelScope.launch {
            var tick = 0L
            while (true) {
                kotlinx.coroutines.delay(5000)
                tick++
                // Questions can be asked any moment and their SSE event gets lost
                // while the app is backgrounded — re-read the queue every ~15s.
                if (tick % 3 == 1L) refreshPendingQuestions()
                val st = runCatching { api.sessionStatus() }.getOrNull() ?: continue
                val serverBusy: (String) -> Boolean = { sid ->
                    val type = (st[sid] as? JsonObject)?.str("type")
                    type == "busy" || type == "retry"
                }
                // 1) reconcile busy for EVERY session the server lists as busy:
                //    turns may run on the PC while the user is elsewhere in the app.
                for ((sid, raw) in st) {
                    if ((raw as? JsonObject)?.str("type").let { it == "busy" || it == "retry" }) {
                        if (busyBySession[sid] != true) setBusy(sid, true)
                        if (tailPollJobs[sid]?.isActive != true) startTailPolling(sid)
                    }
                }
                // 2) and drop busy we still hold for sessions the server no longer
                //    lists — unless their tail poll is alive (host-run turns never
                //    appear in the status map). This is what unsticks "Processing"
                //    when a turn died without emitting anything.
                for (sid in busyBySession.filterValues { it }.keys.toList()) {
                    if (serverBusy(sid)) continue
                    if (tailPollJobs[sid]?.isActive == true) continue
                    setBusy(sid, false)
                }
                // 3) send watchdog — only for the chat on screen
                val sessionId = _chat.value.sessionId
                if (sessionId != null && _chat.value.busy && promptSentAt > 0 &&
                    _chat.value.awaitingUpstream && !serverBusy(sessionId)
                ) {
                    val waited = System.currentTimeMillis() - promptSentAt
                    if (promptAccepted) {
                        // 204 admitted the input — the turn is queued behind the PC
                        // session, give it longer before telling the user anything.
                        if (waited > PROMPT_ACCEPTED_SILENCE_MS) {
                            failSend(
                                sessionId,
                                "opencode accepted the message but the reply has not started yet. " +
                                    "The PC may be using this session — send it again in a minute.",
                            )
                        }
                    } else if (waited > PROMPT_SILENCE_MS) {
                        failSend(
                            sessionId,
                            "No response from opencode — the message was not delivered. " +
                                "Your text is back in the input, send it again.",
                        )
                    }
                    // else: grace period, the turn may still be starting — keep busy
                }
            }
        }
    }
}

// --- PC (Windows) path helpers ---------------------------------------------
// java.io.File on Android splits paths on '/', so it cannot parse the
// backslash paths the PC agent sends: getParent() returns null (that's why
// "go up" silently did nothing and rename failed) and getName() returns the
// whole path. These work on both separators the agent can emit.

internal fun pcIsSep(c: Char): Boolean = c == '\\' || c == '/'

/** Parent of a Windows path; null for "~", a drive root or anything relative. */
internal fun pcParent(raw: String): String? {
    if (raw.isEmpty()) return null
    var end = raw.length
    while (end > 1 && pcIsSep(raw[end - 1])) {
        if (end == 3 && raw[1] == ':') break // keep "C:\" as-is
        end--
    }
    var i = end - 1
    while (i >= 0 && !pcIsSep(raw[i])) i--
    if (i <= 0) return null
    val parent = raw.substring(0, i)
    return if (parent.length == 2 && parent[1] == ':') "$parent\\" else parent
}

/** dir + name in agent notation: "~" stays "~", "C:\" stays a drive root. */
internal fun pcJoin(dir: String, name: String): String {
    val base = dir.trimEnd('/', '\\')
    return if (base.isEmpty()) name else "$base\\$name"
}

/** Last path component ("C:\Users\x" -> "x"). */
internal fun pcName(path: String): String {
    if (path.isEmpty()) return ""
    var end = path.length
    while (end > 1 && pcIsSep(path[end - 1])) end--
    val trimmed = path.substring(0, end)
    var i = trimmed.length - 1
    while (i >= 0 && !pcIsSep(trimmed[i])) i--
    return trimmed.substring(i + 1)
}
