package chat.mural.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
internal const val MAX_RESPONSE_BYTES = 1_048_576L
private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

/** Sends a request and parses a bounded JSON object body; cancellation cancels the call even mid-body. */
internal suspend fun OkHttpClient.postJson(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use {
                    if (it.code !in 200..299) throw APIClient.APIException.Http(it.code)
                    val payload = it.readBoundedBody()
                    try { LENIENT_JSON.parseToJsonElement(payload).jsonObject }
                    catch (_: Exception) { throw APIClient.APIException.InvalidResponse }
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    })
}

/** Reads at most MAX_RESPONSE_BYTES; anything longer is rejected before it is buffered. */
internal fun Response.readBoundedBody(): String {
    val responseBody = body ?: throw APIClient.APIException.InvalidResponse
    if (responseBody.contentLength() > MAX_RESPONSE_BYTES) throw APIClient.APIException.InvalidResponse
    val source = responseBody.source()
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val count = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1L - total))
        if (count == -1L) break
        total += count
        if (total > MAX_RESPONSE_BYTES) throw APIClient.APIException.InvalidResponse
    }
    return buffer.readString(Charsets.UTF_8)
}
