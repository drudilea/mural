package chat.mural.network

import chat.mural.core.SourceLink
import kotlinx.serialization.json.*

/** Decodes a generateContent response into text, safe grounding sources and token usage. */
internal fun decodeGeminiResponse(response: JsonObject): APIResult {
    if ((response["promptFeedback"] as? JsonObject)?.get("blockReason") != null) throw APIClient.APIException.Refused
    val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw APIClient.APIException.Incomplete
    when ((candidate["finishReason"] as? JsonPrimitive)?.contentOrNull) {
        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION" -> throw APIClient.APIException.Refused
        "MAX_TOKENS", "OTHER" -> throw APIClient.APIException.Incomplete
    }
    val text = StringBuilder()
    for (partElement in (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray ?: JsonArray(emptyList())) {
        val part = partElement as? JsonObject ?: continue
        if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
        (part["text"] as? JsonPrimitive)?.contentOrNull?.let(text::append)
    }
    if (text.isEmpty()) throw APIClient.APIException.Incomplete

    val sources = linkedMapOf<String, SourceLink>()
    val grounding = candidate["groundingMetadata"] as? JsonObject
    for (chunkElement in grounding?.get("groundingChunks") as? JsonArray ?: JsonArray(emptyList())) {
        val web = (chunkElement as? JsonObject)?.get("web") as? JsonObject ?: continue
        val uri = (web["uri"] as? JsonPrimitive)?.contentOrNull ?: continue
        if (isSafeSourceUrl(uri)) sources.putIfAbsent(uri, SourceLink((web["title"] as? JsonPrimitive)?.contentOrNull ?: "Source", uri))
    }
    val usage = response["usageMetadata"] as? JsonObject
    return APIResult(
        text = text.toString(),
        sources = sources.values.toList(),
        usage = APIUsage(
            input = ((usage?.get("promptTokenCount") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
            output = ((usage?.get("candidatesTokenCount") as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 1_000_000_000),
            searches = if (grounding != null) 1 else 0,
        ),
    )
}
