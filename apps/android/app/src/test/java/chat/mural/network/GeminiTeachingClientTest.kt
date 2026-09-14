package chat.mural.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiTeachingClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: GeminiTeachingClient
    private val key = "AIza" + "k".repeat(35)

    @Before fun setup() {
        server = MockWebServer(); server.start()
        api = GeminiTeachingClient({ key }, OkHttpClient.Builder().followRedirects(false).build(), server.url("/v1beta/"))
    }
    @After fun teardown() { server.shutdown() }

    private fun response(text: String = "Hola", extra: String = "") =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},"finishReason":"STOP"$extra}],
            "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":7}}"""

    @Test fun postsToGenerateContentWithKeyHeaderSystemInstructionAndUsage() = runBlocking {
        server.enqueue(MockResponse().setBody(response()))
        val result = api.respond("policy", "hello")
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", request.path)
        assertEquals(key, request.getHeader("x-goog-api-key"))
        assertNull(request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("policy", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val content = body["contents"]!!.jsonArray[0].jsonObject
        assertEquals("user", content["role"]!!.jsonPrimitive.content)
        assertEquals("hello", content["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1400, body["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        assertNull(body["tools"])
        assertEquals("Hola", result.text); assertEquals(APIUsage(12, 7, 0), result.usage)
    }

    @Test fun schemaRequestsJsonOutputWithTheGivenSchema() = runBlocking {
        server.enqueue(MockResponse().setBody(response("""{\"a\":1}""")))
        val schema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("a", buildJsonObject { put("type", "integer") }) }) }
        api.respond("p", "q", schema = schema)
        val config = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals(schema, config["responseJsonSchema"])
        assertEquals(2200, config["maxOutputTokens"]!!.jsonPrimitive.int)
    }

    @Test fun searchAddsGoogleSearchAndKeepsSafeDeduplicatedSources() = runBlocking {
        server.enqueue(MockResponse().setBody(response("news", extra = ""","groundingMetadata":{"groundingChunks":[
            {"web":{"uri":"http://bad.example","title":"Insecure"}},
            {"web":{"uri":"https://user@example.com","title":"UserInfo"}},
            {"web":{"uri":"https://example.com","title":"Good"}},
            {"web":{"uri":"https://example.com","title":"Duplicate"}}]}""")))
        val result = api.respond("p", "q", search = true)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertNotNull(body["tools"]!!.jsonArray[0].jsonObject["googleSearch"])
        assertEquals(1, result.sources.size); assertEquals("Good", result.sources.single().title)
        assertEquals(1, result.usage.searches)
    }

    @Test fun blockedRefusedEmptyAndMalformedResultsDoNotBecomeReplies() = runBlocking {
        val bodies = listOf(
            "{}", "not json",
            """{"promptFeedback":{"blockReason":"SAFETY"}}""",
            """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"hidden"}]}}]}""",
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setBody(body))
            try { api.respond("p", "q"); fail("bad result accepted: $body") } catch (_: APIClient.APIException) { }
        }
    }

    @Test fun truncatedRepliesAreRejectedEvenWhenTheyCarryText() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"half a sen"}]}}]}"""))
        try { api.respond("p", "q"); fail("truncated reply accepted") } catch (e: APIClient.APIException) { assertSame(APIClient.APIException.Incomplete, e) }
    }

    @Test fun thoughtPartsAreSkippedButVisibleTextIsKept() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"hidden"},{"text":"Hola "},{"text":"mundo"}]}}]}"""))
        assertEquals("Hola mundo", api.respond("p", "q").text)
    }

    @Test fun httpErrorsAndMissingKeyMapToTheSharedExceptions() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try { api.respond("p", "q"); fail("401 accepted") } catch (e: APIClient.APIException.Http) { assertEquals(401, e.status) }
        server.enqueue(MockResponse().setResponseCode(429))
        try { api.respond("p", "q"); fail("429 accepted") } catch (e: APIClient.APIException.Http) { assertEquals(429, e.status) }
        val missing = GeminiTeachingClient({ null }, OkHttpClient(), server.url("/v1beta/"))
        try { missing.respond("p", "q"); fail("missing key accepted") } catch (_: APIClient.APIException.MissingKey) { }
        assertEquals(2, server.requestCount)
    }

    @Test fun redirectsAreNotFollowed() = runBlocking {
        val production = GeminiTeachingClient({ key }, defaultJsonClient(), server.url("/v1beta/"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        try { production.respond("p", "q"); fail("accepted redirect") } catch (e: APIClient.APIException.Http) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }
}
