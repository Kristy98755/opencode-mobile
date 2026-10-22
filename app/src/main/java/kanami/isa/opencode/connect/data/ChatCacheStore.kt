package kanami.isa.opencode.connect.data

import android.content.Context
import kanami.isa.opencode.connect.net.MessageWithParts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CachedChat(
    val messages: List<MessageWithParts> = emptyList(),
    val oldest: String = "",
    val hasMore: Boolean = true,
    val at: Long = 0,
)

/**
 * Message cache. Messages are immutable and can only be appended to, so a cached
 * entry only ever needs to be extended with newer messages / longer parts.
 */
class ChatCacheStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir: File = File(context.filesDir, "chat_cache").apply { mkdirs() }

    private fun fileFor(sessionId: String): File {
        val safe = sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir, "$safe.json")
    }

    suspend fun load(sessionId: String): CachedChat? = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(sessionId)
            if (!f.exists()) return@runCatching null
            json.decodeFromString<CachedChat>(f.readText())
        }.getOrNull()
    }

    suspend fun save(sessionId: String, chat: CachedChat) = withContext(Dispatchers.IO) {
        runCatching { fileFor(sessionId).writeText(json.encodeToString(chat)) }
    }
}
