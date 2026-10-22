package kanami.isa.opencode.connect.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

object Keys {
    val RELAY_URL = stringPreferencesKey("relay_url")
    val RELAY_TOKEN = stringPreferencesKey("relay_token")
    val AGENT = stringPreferencesKey("agent")
    val PROVIDER = stringPreferencesKey("provider")
    val MODEL = stringPreferencesKey("model")
    val PROJECTS_MODE = booleanPreferencesKey("projects_mode")
}

class SettingsRepository(private val context: Context) {

    private val ds: Flow<Preferences> = context.dataStore.data

    val relayUrl: Flow<String> = ds.map { it[Keys.RELAY_URL] ?: "" }
    val relayToken: Flow<String> = ds.map { it[Keys.RELAY_TOKEN] ?: "" }
    val agent: Flow<String> = ds.map { it[Keys.AGENT] ?: "" }
    val provider: Flow<String> = ds.map { it[Keys.PROVIDER] ?: "" }
    val model: Flow<String> = ds.map { it[Keys.MODEL] ?: "" }

    suspend fun relayConfig(): Pair<String, String> {
        val p = ds.first()
        return (p[Keys.RELAY_URL] ?: "") to (p[Keys.RELAY_TOKEN] ?: "")
    }

    suspend fun saveRelay(url: String, token: String) {
        context.dataStore.edit {
            it[Keys.RELAY_URL] = url.trim()
            it[Keys.RELAY_TOKEN] = token.trim()
        }
    }

    suspend fun saveModel(provider: String, model: String) {
        context.dataStore.edit {
            it[Keys.PROVIDER] = provider
            it[Keys.MODEL] = model
        }
    }

    suspend fun saveAgent(agent: String) {
        context.dataStore.edit { it[Keys.AGENT] = agent }
    }

    suspend fun projectsMode(): Boolean = ds.first()[Keys.PROJECTS_MODE] ?: false

    suspend fun saveProjectsMode(value: Boolean) {
        context.dataStore.edit { it[Keys.PROJECTS_MODE] = value }
    }

    private fun draftKey(sessionId: String) = stringPreferencesKey("draft_$sessionId")

    suspend fun getDraft(sessionId: String): String {
        if (sessionId.isEmpty()) return ""
        return ds.first()[draftKey(sessionId)] ?: ""
    }

    suspend fun setDraft(sessionId: String, text: String) {
        if (sessionId.isEmpty()) return
        context.dataStore.edit { prefs ->
            val key = draftKey(sessionId)
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
    }
}
