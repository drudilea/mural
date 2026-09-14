package chat.mural.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Text helpers over Gemini generateContent; the same contract APIClient fulfils for OpenAI. */
class GeminiTeachingClient internal constructor(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultJsonClient(),
    private val baseUrl: HttpUrl = GEMINI_BASE_URL,
) : TeachingClient {
    constructor(credentials: CredentialStore) : this(credentials::read)

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult {
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject { putJsonArray("parts") { add(buildJsonObject { put("text", instructions) }) } })
            putJsonArray("contents") {
                add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", input) }) } })
            }
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", if (schema == null) 1_400 else 2_200)
                if (schema != null) { put("responseMimeType", "application/json"); put("responseJsonSchema", schema) }
            })
            if (search) putJsonArray("tools") { add(buildJsonObject { put("googleSearch", buildJsonObject { }) }) }
        }
        val key = readCredential() ?: throw APIClient.APIException.MissingKey
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments("models/$TEACHER_MODEL:generateContent").build())
            .header("x-goog-api-key", key)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return decodeGeminiResponse(client.postJson(request))
    }

    companion object {
        const val TEACHER_MODEL = "gemini-3.8-flash"
        private val GEMINI_BASE_URL = HttpUrl.Builder()
            .scheme("https").host("generativelanguage.googleapis.com")
            .addPathSegment("v1beta").addPathSegment("")
            .build()
    }
}
