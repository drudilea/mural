package chat.mural.network

import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Pure translation between Gemini Live JSON and the session.* events MuralViewModel already handles. */
internal class GeminiLiveTranslator(private val nowMillis: () -> Long = System::currentTimeMillis) {
    private var connectedAt = 0L
    private var lastEndMs = 0
    private val pendingCalls = mutableSetOf<String>()

    data class Inbound(
        val events: List<JsonObject> = emptyList(),
        val audio: List<ByteArray> = emptyList(),
        val interrupted: Boolean = false,
        val goAway: Boolean = false,
    )

    fun setup(instructions: String): JsonObject = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", "models/$LIVE_MODEL")
            put("generationConfig", buildJsonObject {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject { put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", VOICE) }) })
                })
            })
            put("systemInstruction", buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", instructions) }) } })
            putJsonArray("tools") {
                add(buildJsonObject {
                    putJsonArray("functionDeclarations") {
                        add(buildJsonObject {
                            put("name", CONTEXT_FUNCTION)
                            put("description", "Ask the app for a short, sourced brief when the learner raises current events, facts you are unsure of, or cultural details worth verifying. Keep the conversation going while it arrives.")
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject { put("reason", buildJsonObject { put("type", "string") }) })
                                putJsonArray("required") { add(JsonPrimitive("reason")) }
                            })
                        })
                    }
                })
            }
            put("inputAudioTranscription", buildJsonObject { })
            put("outputAudioTranscription", buildJsonObject { })
        })
    }

    /** Prior turns from ConversationHistory.messages, added as context without asking for a reply. */
    fun history(history: JsonArray): JsonObject? {
        val turns = history.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val text = (item["content"] as? JsonArray)
                ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
                ?.joinToString("") ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            buildJsonObject {
                put("role", if ((item["role"] as? JsonPrimitive)?.contentOrNull == "user") "user" else "model")
                putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
            }
        }
        if (turns.isEmpty()) return null
        return buildJsonObject { put("clientContent", buildJsonObject { put("turns", JsonArray(turns)); put("turnComplete", false) }) }
    }

    fun audioChunk(pcm16k: ByteArray): JsonObject = buildJsonObject {
        put("realtimeInput", buildJsonObject {
            put("audio", buildJsonObject {
                put("mimeType", "audio/pcm;rate=16000")
                put("data", Base64.getEncoder().encodeToString(pcm16k))
            })
        })
    }

    @Synchronized
    fun inbound(message: JsonObject): Inbound {
        if (message.containsKey("setupComplete")) {
            connectedAt = nowMillis(); lastEndMs = 0
            return Inbound(events = listOf(buildJsonObject { put("type", "session.started") }))
        }
        (message["toolCall"] as? JsonObject)?.let { call ->
            val events = (call["functionCalls"] as? JsonArray).orEmpty().mapNotNull { element ->
                val function = element as? JsonObject ?: return@mapNotNull null
                if ((function["name"] as? JsonPrimitive)?.contentOrNull != CONTEXT_FUNCTION) return@mapNotNull null
                val id = (function["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                pendingCalls += id
                buildJsonObject {
                    put("type", "session.delegation.created")
                    put("delegation", buildJsonObject { put("id", id); put("target", "client") })
                }
            }
            return Inbound(events = events)
        }
        (message["toolCallCancellation"] as? JsonObject)?.let { cancel ->
            (cancel["ids"] as? JsonArray).orEmpty().forEach { id -> (id as? JsonPrimitive)?.contentOrNull?.let(pendingCalls::remove) }
            return Inbound()
        }
        if (message.containsKey("goAway")) return Inbound(goAway = true)
        val content = message["serverContent"] as? JsonObject ?: return Inbound()
        val events = mutableListOf<JsonObject>()
        transcript(content["inputTranscription"], "session.input_transcript.delta")?.let(events::add)
        transcript(content["outputTranscription"], "session.output_transcript.delta")?.let(events::add)
        val audio = ((content["modelTurn"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty().mapNotNull { part ->
            val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: return@mapNotNull null
            if ((inline["mimeType"] as? JsonPrimitive)?.contentOrNull?.startsWith("audio/pcm") != true) return@mapNotNull null
            (inline["data"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        }
        return Inbound(events, audio, interrupted = (content["interrupted"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    /** Gemini message for a session.<kind>.append command, or null when nothing should be sent. */
    @Synchronized
    fun outbound(event: JsonObject): JsonObject? {
        val type = (event["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        val content = (event["content"] as? JsonPrimitive)?.contentOrNull ?: return null
        val delegationID = (event["delegation_id"] as? JsonPrimitive)?.contentOrNull
        return when (type) {
            "session.instructions.append" -> userTurn("Instruction from the app, not the learner: $content", complete = true)
            "session.thinking.append" -> userTurn("Context, not spoken text: $content", complete = false)
            "session.commentary.append" -> when {
                delegationID == null -> userTurn("Say this to the learner now, in your own voice: $content", complete = true)
                pendingCalls.remove(delegationID) -> buildJsonObject {
                    put("toolResponse", buildJsonObject {
                        putJsonArray("functionResponses") {
                            add(buildJsonObject {
                                put("id", delegationID); put("name", CONTEXT_FUNCTION)
                                put("response", buildJsonObject { put("brief", content) })
                            })
                        }
                    })
                }
                else -> null
            }
            else -> null
        }
    }

    fun usage(type: String): JsonObject = buildJsonObject {
        put("type", type)
        put("usage", buildJsonObject { put("seconds", elapsedMs() / 1000.0) })
    }

    private fun transcript(element: JsonElement?, type: String): JsonObject? {
        val text = ((element as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
        val end = elapsedMs(); val start = lastEndMs; lastEndMs = end
        return buildJsonObject {
            put("type", type); put("event_id", UUID.randomUUID().toString()); put("delta", text)
            put("start_ms", start); put("end_ms", end)
        }
    }

    private fun userTurn(text: String, complete: Boolean) = buildJsonObject {
        put("clientContent", buildJsonObject {
            putJsonArray("turns") {
                add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", text) }) } })
            }
            put("turnComplete", complete)
        })
    }

    private fun elapsedMs(): Int =
        if (connectedAt == 0L) 0 else (nowMillis() - connectedAt).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        const val LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val VOICE = "Kore"
        const val CONTEXT_FUNCTION = "request_context"
    }
}
