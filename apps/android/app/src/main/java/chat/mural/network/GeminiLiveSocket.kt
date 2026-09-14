package chat.mural.network

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Gemini Live WebSocket without Android dependencies: setup handshake, message routing, close. */
internal class GeminiLiveSocket(
    private val client: OkHttpClient,
    private val endpoint: HttpUrl,
    private val key: String,
    private val translator: GeminiLiveTranslator,
    private val onInbound: (GeminiLiveTranslator.Inbound) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val ready = CompletableDeferred<Unit>()
    private val finished = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null

    /** Opens the socket, sends setup and waits for setupComplete; 401/403 on upgrade become APIException.Http. */
    suspend fun open(instructions: String, timeoutMillis: Long) {
        val url = endpoint.newBuilder().addQueryParameter("key", key).build()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(translator.setup(instructions).toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // A close before setupComplete is a rejected setup, not the end of a live session.
                if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("Gemini closed the socket before setup completed: $code $reason"))
                else if (finished.compareAndSet(false, true)) onClosed()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error: Throwable = when (response?.code) { 401, 403 -> APIClient.APIException.Http(response.code); else -> t }
                if (!ready.isCompleted) ready.completeExceptionally(error)
                else if (finished.compareAndSet(false, true)) onFailure(error)
            }
        })
        try { withTimeout(timeoutMillis) { ready.await() } }
        catch (error: Throwable) { socket?.cancel(); throw error }
    }

    fun send(message: JsonObject): Boolean = ready.isCompleted && !ready.isCancelled && socket?.send(message.toString()) == true

    /** Graceful close initiated by us; onClosed is not called back. */
    fun close() { finished.set(true); socket?.close(1000, "ended") }

    fun cancel() { finished.set(true); socket?.cancel() }

    private fun handle(text: String) {
        if (text.length > MAX_MESSAGE_CHARS) return
        val json = runCatching { LENIENT.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val inbound = translator.inbound(json)
        if (json.containsKey("setupComplete")) { ready.complete(Unit); return }
        onInbound(inbound)
    }

    companion object {
        val LIVE_ENDPOINT: HttpUrl = HttpUrl.Builder().scheme("https").host("generativelanguage.googleapis.com")
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent").build()
        private const val MAX_MESSAGE_CHARS = 2_000_000
        private val LENIENT = Json { ignoreUnknownKeys = true }
    }
}
