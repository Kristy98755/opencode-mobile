package kanami.isa.opencode.connect.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** One chunk of chat history: the same budget the initial tail renders with. */
const val TAIL_CHUNK_CHARS = 2048

class OpencodeApi(private val relay: RelayClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private suspend fun call(
        method: String,
        path: String,
        body: JsonElement? = null,
        timeoutSeconds: Long = 45L,
    ): JsonElement = withContext(Dispatchers.IO) {
        val res = relay.request(method, path, body, timeoutSeconds)
        val el = res.body
        if (res.status !in 200..299) {
            throw ApiException(res.status, extractError(el))
        }
        if (el == null || el is JsonNull) return@withContext JsonNull
        if (el is JsonObject && el.size == 1) {
            val raw = (el["raw"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (raw != null) {
                runCatching { json.parseToJsonElement(raw) }.getOrNull()?.let { return@withContext it }
            }
        }
        el
    }

    private fun extractError(el: JsonElement?): String {
        if (el == null) return "unknown error"
        if (el is JsonObject) {
            val msg = (el["message"] as? JsonPrimitive)?.content
                ?: (el["error"] as? JsonPrimitive)?.content
            val name = (el["name"] as? JsonPrimitive)?.content
            return when {
                msg != null && name != null -> "$name: $msg"
                msg != null -> msg
                name != null -> name
                else -> el.toString()
            }
        }
        return el.toString()
    }

    private suspend inline fun <reified T> decode(el: JsonElement): T =
        withContext(Dispatchers.IO) { json.decodeFromJsonElement(el) }

    suspend fun health(timeoutSeconds: Long = 8L): HealthInfo =
        decode(call("GET", "/global/health", timeoutSeconds = timeoutSeconds))

    suspend fun path(): PathInfo = decode(call("GET", "/path"))

    suspend fun sessions(): List<Session> {
        val el = call("GET", "/api/session?limit=500&order=desc")
        return withContext(Dispatchers.IO) {
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> el["data"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val s = runCatching { json.decodeFromJsonElement<Session>(obj) }.getOrNull()
                    ?: return@mapNotNull null
                if (!s.directory.isNullOrBlank()) return@mapNotNull s
                val dir = ((obj["location"] as? JsonObject)?.get("directory") as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                if (dir.isNullOrBlank()) s else s.copy(directory = dir)
            }
        }
    }

    suspend fun switchModel(sessionId: String, providerID: String, modelID: String) {
        val body = buildJsonObject {
            put("model", buildJsonObject {
                put("providerID", providerID)
                put("id", modelID)
            })
        }
        call("POST", "/api/session/$sessionId/model", body, timeoutSeconds = 15L)
    }

    suspend fun createSession(
        title: String? = null,
        agent: String? = null,
        directory: String? = null,
    ): Session {
        val body = buildJsonObject {
            if (title != null) put("title", title)
            if (agent != null) put("agent", agent)
        }
        val path = if (directory.isNullOrBlank()) "/session"
        else "/session?directory=" + encodeQuery(directory)
        return decode(call("POST", path, body))
    }

    suspend fun deleteSession(id: String) {
        call("DELETE", "/session/$id")
    }

    suspend fun messagesTail(sessionId: String, chars: Int = TAIL_CHUNK_CHARS): MessagesPage {
        val el = call("GET", "/session/$sessionId/messages-tail?chars=$chars", timeoutSeconds = 90L)
        return withContext(Dispatchers.IO) { parseMessagesPage(el) }
    }

    /**
     * Page of history before `before`. `chars > 0` caps the whole page to that
     * many characters (boundary message stays head-truncated) — "Load more"
     * sends TAIL_CHUNK_CHARS so the rest never arrives full-size in one go;
     * `chars = 0` returns full messages ("Load full conversation", fragment expand).
     */
    suspend fun messagesPage(
        sessionId: String,
        before: String,
        limit: Int = 25,
        chars: Int = 0,
    ): MessagesPage {
        var path = "/session/$sessionId/messages-tail?before=" + encodeQuery(before) + "&limit=$limit"
        if (chars > 0) path += "&chars=$chars"
        val el = call("GET", path, timeoutSeconds = 60L)
        return withContext(Dispatchers.IO) { parseMessagesPage(el) }
    }

    private fun parseMessagesPage(el: JsonElement): MessagesPage {
        val obj = el as? JsonObject ?: return MessagesPage()
        val arr = obj["messages"] as? JsonArray ?: JsonArray(emptyList())
        val messages = arr.mapNotNull { item -> parseMessage(item) }
        val hasMoreEl = obj["hasMore"] as? JsonPrimitive
        return MessagesPage(
            messages = messages,
            hasMore = hasMoreEl?.booleanOrNull == true,
            oldest = (obj["oldest"] as? JsonPrimitive)?.content ?: "",
            inFlight = (obj["inFlight"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    private fun parseMessage(item: JsonElement): MessageWithParts? {
        val obj = item as? JsonObject ?: return null
        val infoEl = obj["info"] ?: return null
        val partsEl = obj["parts"] as? JsonArray ?: JsonArray(emptyList())
        val info = runCatching { json.decodeFromJsonElement<MessageInfo>(infoEl) }.getOrNull() ?: return null
        val parts = partsEl.mapNotNull { p ->
            runCatching { json.decodeFromJsonElement<Part>(p) }.getOrNull()
        }
        val truncated = (obj["truncated"] as? JsonPrimitive)?.booleanOrNull == true
        val fullChars = (obj["fullChars"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return MessageWithParts(info, parts, truncated, fullChars)
    }

    private fun promptBody(
        text: String,
        agent: String?,
        fileParts: List<FileAttachment>,
    ): JsonElement = buildJsonObject {
        put("parts", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
            fileParts.forEach { f ->
                add(buildJsonObject {
                    put("type", "file")
                    put("mime", f.mime)
                    put("filename", f.name)
                    put("url", f.url)
                })
            }
        })
        if (agent != null) put("agent", agent)
    }

    /**
     * Durable v2 admission: the input lands in `session_input` and the server
     * schedules the agent-loop itself. The legacy `prompt_async` wake is
     * in-memory and silently lost when the session's runner lives in another
     * opencode process (desktop app and npm serve share one SQLite — seen in
     * the wild: 204 + persisted message, zero `loop`/`process` lines for 8h).
     * Falls back to legacy only for servers without the v2 API.
     */
    suspend fun promptAsync(
        sessionId: String,
        text: String,
        agent: String? = null,
        fileParts: List<FileAttachment> = emptyList(),
    ) {
        try {
            if (agent != null) {
                // session-scoped agent switch; v2 prompt bodies carry no agent
                call(
                    "POST",
                    "/api/session/$sessionId/agent",
                    buildJsonObject { put("agent", agent) },
                    timeoutSeconds = 10L,
                )
            }
            call(
                "POST",
                "/api/session/$sessionId/prompt",
                buildJsonObject {
                    put(
                        "prompt",
                        buildJsonObject {
                            put("text", text)
                            if (fileParts.isNotEmpty()) {
                                put(
                                    "files",
                                    buildJsonArray {
                                        fileParts.forEach { f ->
                                            add(buildJsonObject {
                                                put("uri", f.url)
                                                put("name", f.name)
                                            })
                                        }
                                    },
                                )
                            }
                        },
                    )
                    // append behind any running turn — matches legacy behavior
                    put("delivery", "queue")
                },
                timeoutSeconds = 30L,
            )
            return
        } catch (e: ApiException) {
            if (e.status != 404 && e.status != 405) throw e
        }
        call(
            "POST",
            "/session/$sessionId/prompt_async",
            promptBody(text, agent, fileParts),
            timeoutSeconds = 30L,
        )
    }

    suspend fun prompt(
        sessionId: String,
        text: String,
        agent: String? = null,
        fileParts: List<FileAttachment> = emptyList(),
    ): MessageWithParts? {
        val el = call(
            "POST",
            "/session/$sessionId/message",
            promptBody(text, agent, fileParts),
            timeoutSeconds = 900L,
        )
        return withContext(Dispatchers.IO) {
            val obj = el as? JsonObject ?: return@withContext null
            val infoEl = obj["info"] ?: return@withContext null
            val partsEl = obj["parts"] as? JsonArray ?: JsonArray(emptyList())
            val info = runCatching { json.decodeFromJsonElement<MessageInfo>(infoEl) }.getOrNull()
                ?: return@withContext null
            val parts = partsEl.mapNotNull { p ->
                runCatching { json.decodeFromJsonElement<Part>(p) }.getOrNull()
            }
            MessageWithParts(info, parts)
        }
    }

    suspend fun abort(sessionId: String) {
        call("POST", "/session/$sessionId/abort", timeoutSeconds = 10L)
    }

    suspend fun providers(): ProvidersResponse = decode(call("GET", "/provider"))

    suspend fun agents(): List<AgentInfo> {
        val el = call("GET", "/agent")
        return withContext(Dispatchers.IO) {
            val arr = when (el) {
                is JsonArray -> el
                else -> JsonArray(emptyList())
            }
            json.decodeFromJsonElement<List<AgentInfo>>(arr)
        }
    }

    suspend fun files(path: String): List<FileNode> {
        val el = call("GET", "/file?path=" + encodeQuery(path))
        return withContext(Dispatchers.IO) {
            val arr = el as? JsonArray ?: JsonArray(emptyList())
            json.decodeFromJsonElement<List<FileNode>>(arr)
        }
    }

    suspend fun fileContent(path: String): FileContent = decode(
        call("GET", "/file/content?path=" + encodeQuery(path))
    )

    suspend fun moveFile(from: String, to: String) = fileTransfer("/file/move", from, to)

    suspend fun copyFile(from: String, to: String) = fileTransfer("/file/copy", from, to)

    private suspend fun fileTransfer(op: String, from: String, to: String) {
        call(
            "POST",
            op,
            buildJsonObject {
                put("from", from)
                put("to", to)
            },
            timeoutSeconds = 120L,
        )
    }

    /** rm — the agent only ever lands it in the Windows recycle bin, never a hard delete. */
    suspend fun deleteFile(path: String) {
        call(
            "POST",
            "/file/delete",
            buildJsonObject { put("path", path) },
            timeoutSeconds = 180L,
        )
    }

    /** mkdir — one level, the agent answers 409 when the name is already taken. */
    suspend fun createFolder(path: String) {
        call(
            "POST",
            "/file/mkdir",
            buildJsonObject { put("path", path) },
            timeoutSeconds = 60L,
        )
    }

    /** Zip one or more files/folders into a password-less archive (Compress-Archive). */
    suspend fun fileZip(from: List<String>, to: String) {
        call(
            "POST",
            "/file/zip",
            buildJsonObject {
                put("from", buildJsonArray { from.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("to", to)
            },
            timeoutSeconds = 300L,
        )
    }

    /** Upload a phone file to the PC in 64KB base64 chunks (mirrors /file/raw). */
    suspend fun uploadFile(path: String, bytes: ByteArray, onProgress: ((Long, Long) -> Unit)? = null) {
        val total = bytes.size.toLong()
        var offset = 0L
        do {
            val end = minOf(offset + 64L * 1024L, total).toInt()
            val chunk = bytes.copyOfRange(offset.toInt(), end)
            call(
                "POST",
                "/file/upload",
                buildJsonObject {
                    put("path", path)
                    put("offset", offset)
                    put("data", android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP))
                    put("eof", end.toLong() == total)
                },
                timeoutSeconds = 120L,
            )
            offset = end.toLong()
            onProgress?.invoke(offset, total)
        } while (offset < total)
    }

    /** Next base64 chunk of a file for the download loop (`eof` ends it). */
    suspend fun fileChunk(path: String, offset: Long, len: Int = 65536): FileChunk = decode(
        call(
            "GET",
            "/file/raw?path=" + encodeQuery(path) + "&offset=$offset&len=$len",
            timeoutSeconds = 60L,
        )
    )

    suspend fun replyPermission(requestId: String, reply: String, message: String? = null) {
        val body = buildJsonObject {
            put("reply", reply)
            if (message != null) put("message", message)
        }
        call("POST", "/permission/$requestId/reply", body, timeoutSeconds = 10L)
    }

    suspend fun replyQuestion(sessionId: String, requestId: String, answers: List<List<String>>) {
        val body = buildJsonObject {
            put("answers", buildJsonArray {
                answers.forEach { one ->
                    add(buildJsonArray { one.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }
        // v2 session-scoped endpoint avoids the ?directory= scoping that made
        // the old /question/{id}/reply land in the wrong workspace (404)
        call("POST", "/api/session/$sessionId/question/$requestId/reply", body, timeoutSeconds = 10L)
    }

    suspend fun rejectQuestion(sessionId: String, requestId: String) {
        call("POST", "/api/session/$sessionId/question/$requestId/reject", timeoutSeconds = 10L)
    }

    /** Questions the desktop agent is still waiting on (survives an app restart). */
    suspend fun pendingQuestions(): List<kanami.isa.opencode.connect.QuestionRequest> {
        val el = call("GET", "/question", timeoutSeconds = 15L)
        return withContext(Dispatchers.IO) {
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> el["data"] as? JsonArray ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            arr.mapNotNull {
                runCatching { json.decodeFromJsonElement<kanami.isa.opencode.connect.QuestionRequest>(it) }.getOrNull()
            }
        }
    }

    suspend fun sessionStatus(): JsonObject {
        val el = call("GET", "/session/status")
        return el as? JsonObject ?: buildJsonObject { }
    }

    private fun encodeQuery(v: String): String {
        val bytes = v.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch in "-_.~" || ch == '\\' || ch == '/' || ch == ':') {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }
}

class ApiException(val status: Int, message: String) : Exception("HTTP $status: $message")

data class FileAttachment(val mime: String, val name: String, val url: String)

data class MessagesPage(
    val messages: List<MessageWithParts> = emptyList(),
    val hasMore: Boolean = false,
    val oldest: String = "",
    /** Agent says a turn is running even when the message store looks frozen. */
    val inFlight: Boolean = false,
)
