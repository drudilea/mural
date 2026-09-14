package chat.mural.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local provenance is separate from exported learning content and contains no credentials.
 * A hosted session cannot be recovered later using a personal API key. */
internal class ConversationProviderStore(context: Context) {
    private val preferences = context.getSharedPreferences("mural_conversation_providers", Context.MODE_PRIVATE)
    data class Snapshot(val hostedIDs: Set<String>, val pendingOwnerID: String?, val selection: ConversationProvider)
    suspend fun read(defaultSelection: ConversationProvider = ConversationProvider.PERSONAL_KEY) = withContext(Dispatchers.IO) {
        Snapshot(preferences.getStringSet("hosted", emptySet())?.toSet().orEmpty(),
            preferences.getString("pending_owner", null),
            runCatching { ConversationProvider.valueOf(preferences.getString("selection", null).orEmpty()) }
                .getOrDefault(defaultSelection))
    }
    suspend fun markHosted(localID: String, ownerID: String) = withContext(Dispatchers.IO) {
        val ids = preferences.getStringSet("hosted", emptySet()).orEmpty() + localID
        check(preferences.edit().putStringSet("hosted", ids).putString("pending_owner", ownerID).commit())
    }
    suspend fun clearPending() = withContext(Dispatchers.IO) { check(preferences.edit().remove("pending_owner").commit()) }
    suspend fun select(provider: ConversationProvider) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("selection", provider.name).commit())
    }
    suspend fun readAIProvider(): AIProvider = withContext(Dispatchers.IO) {
        AIProvider.fromName(preferences.getString("ai_provider", null))
    }
    suspend fun selectAIProvider(provider: AIProvider) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("ai_provider", provider.name).commit())
    }
    /** AI providers the learner has explicitly consented to send their audio and text to. */
    suspend fun consentedProviders(): Set<AIProvider> = withContext(Dispatchers.IO) {
        preferences.getStringSet("ai_consent_providers", emptySet()).orEmpty()
            .mapNotNull { name -> AIProvider.fromName(name).takeIf { it.name == name } }.toSet()
    }
    /** Records consent for one provider; consent for the others is unchanged. */
    suspend fun markConsented(provider: AIProvider) = withContext(Dispatchers.IO) {
        val names = preferences.getStringSet("ai_consent_providers", emptySet()).orEmpty() + provider.name
        check(preferences.edit().putStringSet("ai_consent_providers", names).commit())
    }
}
