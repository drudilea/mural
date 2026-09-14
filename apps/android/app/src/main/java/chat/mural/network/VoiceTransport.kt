package chat.mural.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** What the conversation view model needs from any real-time voice backend. Events use the session.* vocabulary. */
interface VoiceTransport {
    var onEvent: ((JsonObject) -> Unit)?
    var onFailure: ((String) -> Unit)?
    var onLevels: ((Double, Double) -> Unit)?
    val started: Boolean
    val isMuted: Boolean
    suspend fun connect(api: LiveSessionProvider, instructions: String, history: JsonArray = JsonArray(emptyList()), language: String? = null)
    /** True means accepted for delivery, not delivered. */
    fun send(event: JsonObject): Boolean
    fun mute(muted: Boolean)
    fun close()
    fun disconnect()
}
