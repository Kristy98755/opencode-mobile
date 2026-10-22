package kanami.isa.opencode.connect.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = false
}

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0,
    val archived: Long? = null,
)

@Serializable
data class ModelRef(
    val providerID: String = "",
    val id: String = "",
    val modelID: String = "",
    val variant: String? = null,
)

@Serializable
data class Session(
    val id: String,
    val slug: String? = null,
    val title: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val agent: String? = null,
    val model: ModelRef? = null,
    val time: SessionTime? = null,
    val cost: Double? = null,
    val parentID: String? = null,
    val projectID: String? = null,
)

@Serializable
data class Part(
    val id: String = "",
    val sessionID: String = "",
    val messageID: String = "",
    val type: String = "",
    val text: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: JsonObject? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val files: List<String>? = null,
    val reason: String? = null,
    val cost: Double? = null,
    val time: JsonObject? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class MessageInfo(
    val id: String = "",
    val sessionID: String = "",
    val role: String = "",
    val time: JsonObject? = null,
    // "tool-calls" while a step chain keeps going, "stop"/"error" at the end —
    // the only reliable turn-finish signal for host-run turns (no SSE for those).
    val finish: String? = null,
    val error: JsonObject? = null,
    val agent: String? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val cost: Double? = null,
    val tokens: JsonObject? = null,
    val summary: JsonObject? = null,
)

@Serializable
data class MessageWithParts(
    val info: MessageInfo,
    val parts: List<Part>,
    val truncated: Boolean = false,
    val fullChars: Int = 0,
)

@Serializable
data class AgentInfo(
    val name: String = "",
    val description: String? = null,
    val mode: String = "primary",
    val native: Boolean? = null,
    val hidden: Boolean? = null,
    val model: ModelRef? = null,
)

@Serializable
data class ModelInfo(
    val id: String = "",
    val providerID: String = "",
    val name: String? = null,
    val family: String? = null,
)

@Serializable
data class ProviderInfo(
    val id: String = "",
    val name: String? = null,
    val source: String? = null,
    val models: Map<String, ModelInfo> = emptyMap(),
)

@Serializable
data class ProvidersResponse(
    val all: List<ProviderInfo> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class FileNode(
    val name: String = "",
    val path: String = "",
    val absolute: String = "",
    val type: String = "file",
    val ignored: Boolean = false,
)

@Serializable
data class FileContent(
    val type: String = "text",
    val content: String? = null,
    val diff: String? = null,
)

@Serializable
data class PathInfo(
    val home: String = "",
    val state: String = "",
    val config: String = "",
    val worktree: String = "",
    val directory: String = "",
)

@Serializable
data class HealthInfo(
    val healthy: Boolean = false,
    val version: String? = null,
)

sealed class RelayState {
    data object Idle : RelayState()
    data class Connecting(val url: String) : RelayState()
    data class Ready(val url: String) : RelayState()
    data class Failed(val reason: String) : RelayState()
}

class RelayResponse(val status: Int, val body: JsonElement?)
