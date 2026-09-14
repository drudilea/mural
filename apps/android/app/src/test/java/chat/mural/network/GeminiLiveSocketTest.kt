package chat.mural.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiLiveSocketTest {
    private lateinit var server: MockWebServer
    private val key = "AIza" + "k".repeat(35)
    private val client = OkHttpClient()

    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown(); client.dispatcher.executorService.shutdown() }

    @Test fun handshakeSendsSetupAndCompletesOnSetupComplete() = runBlocking {
        val setupSeen = CompletableDeferred<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { setupSeen.complete(text); webSocket.send("""{"setupComplete":{}}""") }
        }))
        val inbound = mutableListOf<GeminiLiveTranslator.Inbound>()
        val socket = GeminiLiveSocket(client, server.url("/ws"), key, GeminiLiveTranslator { 1_000L }, { inbound += it }, {}, {})
        socket.open("Teach Spanish", 5_000)
        assertEquals("/ws?key=$key", server.takeRequest().path)
        val setup = Json.parseToJsonElement(setupSeen.await()).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-3.1-flash-live-preview", setup["model"]!!.jsonPrimitive.content)
        assertTrue(inbound.isEmpty())
        socket.cancel()
    }

    @Test fun rejectedUpgradeSurfacesTheHttpStatusForKeyProblems() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val socket = GeminiLiveSocket(client, server.url("/ws"), key, GeminiLiveTranslator(), {}, {}, {})
        try { socket.open("x", 5_000); fail("upgrade accepted") } catch (e: APIClient.APIException.Http) { assertEquals(403, e.status) }
    }

    @Test fun messagesAfterSetupReachTheListenerAndServerCloseIsReported() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"setupComplete":{}}""")
                webSocket.send("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""")
                webSocket.close(1000, "done")
            }
        }))
        val received = Channel<GeminiLiveTranslator.Inbound>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        val socket = GeminiLiveSocket(client, server.url("/ws"), key, GeminiLiveTranslator { 1_000L }, { received.trySend(it) }, { closed.complete(Unit) }, {})
        socket.open("x", 5_000)
        val first = withTimeout(5_000) { received.receive() }
        assertEquals("session.output_transcript.delta", first.events.single()["type"]!!.jsonPrimitive.content)
        withTimeout(5_000) { closed.await() }
    }

    @Test fun closeBeforeSetupCompleteFailsOpenWithoutReportingASessionClose() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { webSocket.close(1008, "rejected") }
        }))
        var closedCalls = 0
        val socket = GeminiLiveSocket(client, server.url("/ws"), key, GeminiLiveTranslator(), {}, { closedCalls++ }, {})
        val startedAt = System.nanoTime()
        val error = try { socket.open("x", 5_000); null } catch (e: Throwable) { e }
        assertNotNull("setup accepted", error)
        assertFalse("open() waited for the timeout", error is TimeoutCancellationException)
        assertTrue((System.nanoTime() - startedAt) < 3_000_000_000L)
        assertEquals(0, closedCalls)
    }

    @Test fun sendReturnsFalseBeforeOpenAndTrueAfter() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { if (text.contains("setup")) webSocket.send("""{"setupComplete":{}}""") }
        }))
        val socket = GeminiLiveSocket(client, server.url("/ws"), key, GeminiLiveTranslator(), {}, {}, {})
        assertFalse(socket.send(buildJsonObject { put("x", 1) }))
        socket.open("x", 5_000)
        assertTrue(socket.send(buildJsonObject { put("x", 1) }))
        socket.cancel()
    }
}
