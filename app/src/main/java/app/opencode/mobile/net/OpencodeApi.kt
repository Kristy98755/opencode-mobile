package app.opencode.mobile.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

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
    ): JsonElement {
        val res = relay.request(method, path, body, timeoutSeconds)
        val el = res.body
        if (res.status !in 200..299) {
            throw ApiException(res.status, extractError(el))
        }
        if (el == null || el is JsonNull) return JsonNull
        return el
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

    private inline fun <reified T> decode(el: JsonElement): T = json.decodeFromJsonElement(el)

    suspend fun health(timeoutSeconds: Long = 8L): HealthInfo =
        decode(call("GET", "/global/health", timeoutSeconds = timeoutSeconds))

    suspend fun path(): PathInfo = decode(call("GET", "/path"))

    suspend fun sessions(): List<Session> {
        val el = call("GET", "/session?limit=200")
        return when (el) {
            is JsonArray -> json.decodeFromJsonElement<List<Session>>(el)
            is JsonObject -> {
                val data = el["data"] as? JsonArray ?: JsonArray(emptyList())
                json.decodeFromJsonElement<List<Session>>(data)
            }
            else -> emptyList()
        }
    }

    suspend fun createSession(title: String? = null, agent: String? = null, model: ModelRef? = null): Session {
        val body = buildJsonObject {
            if (title != null) put("title", title)
            if (agent != null) put("agent", agent)
            if (model != null) {
                put("model", buildJsonObject {
                    put("providerID", model.providerID)
                    put("id", model.id.ifEmpty { model.modelID })
                })
            }
        }
        return decode(call("POST", "/session", body))
    }

    suspend fun deleteSession(id: String) {
        call("DELETE", "/session/$id")
    }

    suspend fun messages(sessionId: String): List<MessageWithParts> {
        val el = call("GET", "/session/$sessionId/message?limit=300")
        val arr = when (el) {
            is JsonArray -> el
            is JsonObject -> el["data"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return arr.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val infoEl = obj["info"] ?: return@mapNotNull null
            val partsEl = obj["parts"] as? JsonArray ?: JsonArray(emptyList())
            val info = runCatching { json.decodeFromJsonElement<MessageInfo>(infoEl) }.getOrNull()
                ?: return@mapNotNull null
            val parts = partsEl.mapNotNull { p ->
                runCatching { json.decodeFromJsonElement<Part>(p) }.getOrNull()
            }
            MessageWithParts(info, parts)
        }
    }

    suspend fun prompt(
        sessionId: String,
        text: String,
        model: ModelRef? = null,
        agent: String? = null,
        fileParts: List<FileAttachment> = emptyList(),
    ): MessageWithParts? {
        val body = buildJsonObject {
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
            if (model != null) {
                put("model", buildJsonObject {
                    put("providerID", model.providerID)
                    put("modelID", model.modelID)
                })
            }
            if (agent != null) put("agent", agent)
        }
        val el = call("POST", "/session/$sessionId/message", body, timeoutSeconds = 30L)
        val obj = el as? JsonObject ?: return null
        val infoEl = obj["info"] ?: return null
        val partsEl = obj["parts"] as? JsonArray ?: JsonArray(emptyList())
        val info = runCatching { json.decodeFromJsonElement<MessageInfo>(infoEl) }.getOrNull() ?: return null
        val parts = partsEl.mapNotNull { p ->
            runCatching { json.decodeFromJsonElement<Part>(p) }.getOrNull()
        }
        return MessageWithParts(info, parts)
    }

    suspend fun abort(sessionId: String) {
        call("POST", "/session/$sessionId/abort", timeoutSeconds = 10L)
    }

    suspend fun providers(): ProvidersResponse = decode(call("GET", "/provider"))

    suspend fun agents(): List<AgentInfo> {
        val el = call("GET", "/agent")
        val arr = when (el) {
            is JsonArray -> el
            else -> JsonArray(emptyList())
        }
        return json.decodeFromJsonElement<List<AgentInfo>>(arr)
    }

    suspend fun files(path: String): List<FileNode> {
        val el = call("GET", "/file?path=" + encodeQuery(path))
        val arr = el as? JsonArray ?: JsonArray(emptyList())
        return json.decodeFromJsonElement<List<FileNode>>(arr)
    }

    suspend fun fileContent(path: String): FileContent = decode(
        call("GET", "/file/content?path=" + encodeQuery(path))
    )

    suspend fun replyPermission(requestId: String, reply: String, message: String? = null) {
        val body = buildJsonObject {
            put("reply", reply)
            if (message != null) put("message", message)
        }
        call("POST", "/permission/$requestId/reply", body, timeoutSeconds = 10L)
    }

    suspend fun replyQuestion(requestId: String, answers: List<List<String>>) {
        val body = buildJsonObject {
            put("answers", buildJsonArray {
                answers.forEach { one ->
                    add(buildJsonArray { one.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }
        call("POST", "/question/$requestId/reply", body, timeoutSeconds = 10L)
    }

    suspend fun rejectQuestion(requestId: String) {
        call("POST", "/question/$requestId/reject", timeoutSeconds = 10L)
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
