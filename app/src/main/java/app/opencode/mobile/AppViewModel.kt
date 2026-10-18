package app.opencode.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opencode.mobile.data.SettingsRepository
import app.opencode.mobile.net.AgentInfo
import app.opencode.mobile.net.ApiException
import app.opencode.mobile.net.FileAttachment
import app.opencode.mobile.net.FileContent
import app.opencode.mobile.net.FileNode
import app.opencode.mobile.net.MessageInfo
import app.opencode.mobile.net.MessageWithParts
import app.opencode.mobile.net.ModelRef
import app.opencode.mobile.net.OpencodeApi
import app.opencode.mobile.net.Part
import app.opencode.mobile.net.PathInfo
import app.opencode.mobile.net.ProvidersResponse
import app.opencode.mobile.net.RelayClient
import app.opencode.mobile.net.RelayException
import app.opencode.mobile.net.RelayState
import app.opencode.mobile.net.Session
import app.opencode.mobile.net.json
import app.opencode.mobile.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    val messages: List<MessageWithParts> = emptyList(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class FilesUiState(
    val path: String = "",
    val nodes: List<FileNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class ViewerState(
    val path: String,
    val content: String,
    val binary: Boolean,
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

    private val _selectedProvider = MutableStateFlow("")
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    private val _selectedModel = MutableStateFlow("")
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _selectedAgent = MutableStateFlow("")
    val selectedAgent: StateFlow<String> = _selectedAgent.asStateFlow()

    private var eventJob: Job? = null
    private var sessionStatusJob: Job? = null
    private var stateCollectJob: Job? = null

    init {
        viewModelScope.launch {
            val (url, token) = settings.relayConfig()
            _selectedProvider.value = settings.provider.first()
            _selectedModel.value = settings.model.first()
            _selectedAgent.value = settings.agent.first()
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
                    refreshSessions()
                    loadMeta()
                    startStatusPolling()
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

    // --- sessions ---

    fun refreshSessions() {
        viewModelScope.launch {
            _sessionsLoading.value = true
            try {
                val list = api.sessions()
                _sessions.value = list.sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
            } catch (e: Exception) {
                _toast.tryEmit("Sessions error: ${e.message}")
            } finally {
                _sessionsLoading.value = false
            }
        }
    }

    fun newSession(onCreated: (Session) -> Unit) {
        viewModelScope.launch {
            try {
                val model = currentModelRef()
                val session = api.createSession(
                    agent = _selectedAgent.value.ifEmpty { null },
                    model = model,
                )
                _sessions.value = listOf(session) + _sessions.value
                onCreated(session)
            } catch (e: Exception) {
                _toast.tryEmit("Create session error: ${e.message}")
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
                _toast.tryEmit("Delete error: ${e.message}")
            }
        }
    }

    private fun currentModelRef(): ModelRef? {
        val p = _selectedProvider.value
        val m = _selectedModel.value
        if (p.isEmpty() || m.isEmpty()) return null
        return ModelRef(providerID = p, id = m, modelID = m)
    }

    // --- chat ---

    fun openChat(session: Session) {
        _chat.value = ChatUiState(sessionId = session.id, title = session.title, loading = true)
        viewModelScope.launch {
            try {
                val msgs = api.messages(session.id)
                _chat.value = _chat.value.copy(
                    messages = sortMessages(msgs),
                    loading = false,
                )
                scrollToBottomIfNeeded()
            } catch (e: Exception) {
                _chat.value = _chat.value.copy(
                    loading = false,
                    error = e.message ?: "load failed",
                )
            }
        }
    }

    fun closeChat() {
        _chat.value = ChatUiState()
    }

    fun sendPrompt(text: String, attachments: List<FileAttachment> = emptyList()) {
        val sessionId = _chat.value.sessionId ?: return
        if (text.isBlank() && attachments.isEmpty()) return
        _chat.value = _chat.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val response = api.prompt(
                    sessionId = sessionId,
                    text = text,
                    model = currentModelRef(),
                    agent = _selectedAgent.value.ifEmpty { null },
                    fileParts = attachments,
                )
                if (response != null) {
                    upsertMessage(response)
                }
            } catch (e: Exception) {
                _chat.value = _chat.value.copy(
                    busy = false,
                    error = e.message ?: "send failed",
                )
            }
        }
    }

    fun abortPrompt() {
        val sessionId = _chat.value.sessionId ?: return
        viewModelScope.launch {
            try {
                api.abort(sessionId)
                _chat.value = _chat.value.copy(busy = false)
            } catch (e: Exception) {
                _toast.tryEmit("Abort error: ${e.message}")
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
        if (msg.info.sessionID != cur.sessionId && cur.sessionId == null) return
        val idx = cur.messages.indexOfFirst { it.info.id == msg.info.id }
        val newList = if (idx >= 0) {
            cur.messages.toMutableList().apply { set(idx, mergeMessages(this[idx], msg)) }
        } else {
            sortMessages(cur.messages + msg)
        }
        _chat.value = cur.copy(messages = newList, error = null)
    }

    private fun mergeMessages(old: MessageWithParts, new: MessageWithParts): MessageWithParts {
        if (old.parts.isEmpty()) return new
        val partMap = LinkedHashMap<String, Part>()
        old.parts.forEach { partMap[it.id] = it }
        new.parts.forEach { partMap[it.id] = it }
        return MessageWithParts(info = new.info, parts = partMap.values.toList())
    }

    private fun updatePart(part: Part) {
        val cur = _chat.value
        if (cur.sessionId == null) return
        if (part.sessionID.isNotEmpty() && part.sessionID != cur.sessionId) return
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
        val newParts = if (pIdx >= 0) {
            msg.parts.toMutableList().apply { set(pIdx, part) }
        } else {
            msg.parts + part
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
        val idx = cur.messages.indexOfFirst { it.info.id == info.id }
        if (idx < 0) {
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

    private fun scrollToBottomIfNeeded() {}

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
                    viewModelScope.launch { updateMessageInfo(mi) }
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
                    if (_chat.value.sessionId == sid) {
                        val busy = statusType == "busy" || statusType == "retry"
                        _chat.value = _chat.value.copy(busy = busy)
                    }
                }
            }
            "session.error" -> {
                val sid = payload.str("sessionID") ?: return
                val err = payload["error"] as? JsonObject
                val msg = err?.str("message") ?: payload.str("message") ?: "session error"
                viewModelScope.launch {
                    if (_chat.value.sessionId == sid) {
                        _chat.value = _chat.value.copy(busy = false, error = msg)
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
                    _question.value = QuestionRequest(id, sid, qs)
                }
            }
            "question.replied", "question.rejected" -> _question.value = null
        }
    }

    private fun onSessionUpdated(s: Session) {
        _sessions.value = _sessions.value
            .map { if (it.id == s.id) s else it }
            .sortedByDescending { it.time?.updated ?: it.time?.created ?: 0L }
        if (_chat.value.sessionId == s.id && _chat.value.title != s.title) {
            _chat.value = _chat.value.copy(title = s.title)
        }
    }

    fun answerPermission(reply: String, message: String? = null) {
        val req = _permission.value ?: return
        viewModelScope.launch {
            try {
                api.replyPermission(req.id, reply, message)
                _permission.value = null
            } catch (e: Exception) {
                _toast.tryEmit("Permission reply error: ${e.message}")
                _permission.value = null
            }
        }
    }

    fun answerQuestion(answers: List<List<String>>) {
        val req = _question.value ?: return
        viewModelScope.launch {
            try {
                api.replyQuestion(req.id, answers)
                _question.value = null
            } catch (e: Exception) {
                _toast.tryEmit("Question reply error: ${e.message}")
                _question.value = null
            }
        }
    }

    fun rejectQuestion() {
        val req = _question.value ?: return
        viewModelScope.launch {
            try {
                api.rejectQuestion(req.id)
            } catch (_: Exception) {
            } finally {
                _question.value = null
            }
        }
    }

    // --- meta ---

    fun loadMeta() {
        viewModelScope.launch {
            runCatching { _providers.value = api.providers() }
                .onFailure { _toast.tryEmit("Providers error: ${it.message}") }
            runCatching {
                val list = api.agents().filter { it.hidden != true }
                _agents.value = list
            }
            if (_selectedProvider.value.isEmpty()) {
                _providers.value?.default?.let { d ->
                    val p = d.keys.firstOrNull()
                    if (p != null) {
                        _selectedProvider.value = p
                        _selectedModel.value = d[p] ?: ""
                    }
                }
            }
        }
    }

    fun selectModel(provider: String, model: String) {
        _selectedProvider.value = provider
        _selectedModel.value = model
        viewModelScope.launch { settings.saveModel(provider, model) }
    }

    fun selectAgent(agent: String) {
        _selectedAgent.value = agent
        viewModelScope.launch { settings.saveAgent(agent) }
    }

    // --- files ---

    fun openFiles(rootPath: String) {
        _files.value = FilesUiState(path = rootPath, loading = true)
        loadDir(rootPath)
    }

    fun loadDir(path: String) {
        viewModelScope.launch {
            _files.value = _files.value.copy(path = path, loading = true, error = null)
            try {
                val nodes = api.files(path)
                _files.value = FilesUiState(
                    path = path,
                    nodes = nodes.sortedWith(
                        compareBy({ it.type != "directory" }, { it.name.lowercase() })
                    ),
                    loading = false,
                )
            } catch (e: Exception) {
                _files.value = _files.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun goUpDir() {
        val cur = _files.value.path
        val parent = java.io.File(cur).parent ?: return
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
                _toast.tryEmit("File error: ${e.message}")
            }
        }
    }

    fun closeViewer() {
        _viewer.value = null
    }

    fun projectDirectory(): String {
        return _chat.value.title?.let { null }
            ?: runCatching { sessions.value.firstOrNull()?.directory }.getOrNull()
            ?: ""
    }

    override fun onCleared() {
        eventJob?.cancel()
        sessionStatusJob?.cancel()
        relay.disconnect()
        super.onCleared()
    }

    private fun startStatusPolling() {
        if (sessionStatusJob != null) return
        sessionStatusJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2500)
                val sessionId = _chat.value.sessionId ?: continue
                if (!_chat.value.busy) continue
                runCatching {
                    val st = api.sessionStatus()
                    val s = (st[sessionId] as? JsonObject)?.str("type")
                    if (s != null) {
                        val busy = s == "busy" || s == "retry"
                        if (_chat.value.sessionId == sessionId && _chat.value.busy != busy) {
                            _chat.value = _chat.value.copy(busy = busy)
                        }
                    }
                }
            }
        }
    }
}
