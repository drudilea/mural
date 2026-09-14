# Gemini Provider on Android Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the Android client talk to Gemini (text via `generateContent`, voice via the Gemini Live WebSocket) as a second, user-selectable provider next to OpenAI, with one API key per provider.

**Architecture:** The view model already talks to text through `TeachingClient` and to voice through five methods and three callbacks on `LiveTransport`. We add a `VoiceTransport` interface with exactly that surface, a Gemini text client, and a Gemini voice transport that is split into a pure JSON translator (Gemini messages ⇄ the `session.*` events the view model already handles), a Context-free WebSocket wrapper, and an Android PCM audio layer. Provider choice lives in `ConversationProviderStore`, keys in one `CredentialStore` per provider.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp 4.12 (HTTP + WebSocket, already a dependency), kotlinx.serialization JSON, `AudioRecord`/`AudioTrack`, JUnit 4 + MockWebServer for tests. Java 21 and Android SDK 36 are installed on this Mac.

**Spec:** `docs/superpowers/specs/2026-09-14-gemini-provider-android-design.md`

## Global Constraints

- Android only. Nothing under `apps/ios/` changes. Teaching prompts (`TeachingPolicy`) do not change, so `scripts/check_cross_platform.py` must keep passing.
- `Preferences` (`core/Models.kt`) is not touched. The parity script compares its fields with Swift; the provider choice is device-local and lives in `ConversationProviderStore` instead (deviation from the spec, decided during planning).
- `LiveTransport.kt` (WebRTC/OpenAI) changes only by implementing `VoiceTransport` (`override` keywords). Its internals cannot be re-tested on a device in this project (no OpenAI key), so they stay untouched.
- Gemini facts fixed by the spec: Live endpoint `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<key>`; Live model `gemini-3.1-flash-live-preview`; audio in PCM16 mono 16 kHz (`audio/pcm;rate=16000`), out PCM16 mono 24 kHz; text endpoint `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent` with header `x-goog-api-key`; structured output via `generationConfig.responseMimeType` + `responseJsonSchema`; grounding via `tools: [{"googleSearch": {}}]`, sources in `groundingMetadata.groundingChunks[].web.{uri,title}`.
- Repo text stays in English (identifiers, comments, strings, commit messages). Commit messages follow the log: imperative, capitalized, no prefix.
- Never stage with `git add -A`/`-u`/`.`; stage explicit paths. Do not touch `main`; work on `gemini-provider-android`.
- Run Gradle from `apps/android`. Create `apps/android/local.properties` (git-ignored) with `sdk.dir=/Users/drudilea/Library/Android/sdk` once (Task 1) so `ANDROID_HOME` is not needed.
- Test command shape: `./gradlew :app:testDebugUnitTest --tests 'fully.qualified.TestClass' -q`. A first Gradle run downloads dependencies and can take several minutes.
- Doc comments on one line where possible. No status markers ("Phase 1", "TODO later") in code comments.

---

## File Map

| Path | Responsibility |
| --- | --- |
| Create `core/AIProvider.kt` | Enum of direct providers: display name, links, model labels, credential storage names, key validation |
| Modify `core/ConversationProviderStore.kt` | Persist the chosen `AIProvider` in the existing SharedPreferences |
| Modify `network/CredentialStore.kt` | One encrypted key per provider; validation delegated to `AIProvider` |
| Create `network/JsonHttp.kt` | Shared bounded body reader and cancellable JSON POST (moved out of `APIClient`) |
| Modify `network/APIClient.kt` | Use `JsonHttp.kt`; neutral exception messages |
| Modify `network/TeachingResponse.kt` | `isSafeSourceUrl` becomes `internal` |
| Create `network/GeminiResponse.kt` | Decode a `generateContent` response into `APIResult` |
| Create `network/GeminiTeachingClient.kt` | `TeachingClient` over `generateContent` |
| Create `network/VoiceTransport.kt` | Interface the view model uses for voice |
| Modify `network/LiveTransport.kt` | `: VoiceTransport` + `override` |
| Create `network/GeminiLiveTranslator.kt` | Pure Gemini Live JSON ⇄ `session.*` events, clock, tool-call ids |
| Create `network/PcmAudioIO.kt` | `AudioRecord`/`AudioTrack` PCM loop behind an interface; RMS level helper |
| Create `network/GeminiLiveSocket.kt` | OkHttp WebSocket: setup handshake, routing, close (no Android deps) |
| Create `network/GeminiLiveTransport.kt` | `VoiceTransport` composing socket + audio + Android audio session |
| Modify `MuralViewModel.kt` | Provider state, client/transport selection, key operations, provider-named errors |
| Modify `ui/SettingsScreen.kt`, `ui/Onboarding.kt`, `ui/MuralApp.kt` | Provider selector, provider-driven links and texts |
| Modify `res/values/strings.xml`, `res/values-es/strings.xml`, `res/values/settings_parity.xml`, `res/values-es/settings_parity.xml` | Parametrize provider name; new strings |
| Modify `androidTest/.../SettingsDetailsTest.kt`, `SettingsParityTest.kt` | Pass format args to the changed strings |
| Tests | `core/AIProviderTest.kt`, `network/GeminiTeachingClientTest.kt`, `network/GeminiLiveTranslatorTest.kt`, `network/PcmAudioIOTest.kt`, `network/GeminiLiveSocketTest.kt` |

All Kotlin paths are relative to `apps/android/app/src/main/java/chat/mural/` (main) and `apps/android/app/src/test/java/chat/mural/` (tests) unless written in full.

---

### Task 1: `AIProvider` enum, per-provider credentials, stored choice

**Files:**
- Create: `apps/android/local.properties`
- Create: `core/AIProvider.kt`
- Modify: `core/ConversationProviderStore.kt`
- Modify: `network/CredentialStore.kt`
- Test: `apps/android/app/src/test/java/chat/mural/core/AIProviderTest.kt`

**Interfaces:**
- Produces: `enum class AIProvider { OPENAI, GEMINI }` with `displayName`, `keyURL`, `usageURL`, `dataURL`, `voiceModelLabel`, `teacherModelLabel`, `credentialPreferences`, `keyAlias`, `fun isValidKey(key: String): Boolean`, `companion fun fromName(name: String?): AIProvider`.
- Produces: `CredentialStore(context: Context, provider: AIProvider = AIProvider.OPENAI)`; `save/read/delete/hasKey` unchanged.
- Produces: `ConversationProviderStore.readAIProvider(): AIProvider` and `selectAIProvider(provider: AIProvider)` (both `suspend`).

- [ ] **Step 1: SDK location for Gradle**

```bash
cd /Users/drudilea/Repos/mural/apps/android
printf 'sdk.dir=/Users/drudilea/Library/Android/sdk\n' > local.properties
git check-ignore -q local.properties && echo ignored
```
Expected: `ignored`.

- [ ] **Step 2: Write the failing test**

Create `apps/android/app/src/test/java/chat/mural/core/AIProviderTest.kt`:

```kotlin
package chat.mural.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIProviderTest {
    @Test fun openAIKeysNeedThePlatformPrefixAndLength() {
        assertTrue(AIProvider.OPENAI.isValidKey("sk-" + "a".repeat(40)))
        assertFalse(AIProvider.OPENAI.isValidKey("AIza" + "a".repeat(35)))
        assertFalse(AIProvider.OPENAI.isValidKey("sk-short"))
        assertFalse(AIProvider.OPENAI.isValidKey("sk-" + "a".repeat(20) + " " + "a".repeat(20)))
    }

    @Test fun geminiKeysAreLongUrlSafeTokens() {
        assertTrue(AIProvider.GEMINI.isValidKey("AIza" + "b".repeat(35)))
        assertTrue(AIProvider.GEMINI.isValidKey("AIza-_" + "b".repeat(33)))
        assertFalse(AIProvider.GEMINI.isValidKey("AIza b" + "b".repeat(35)))
        assertFalse(AIProvider.GEMINI.isValidKey("tooshort"))
        assertFalse(AIProvider.GEMINI.isValidKey("AIza/" + "b".repeat(35)))
    }

    @Test fun unknownStoredNamesFallBackToOpenAI() {
        assertEquals(AIProvider.OPENAI, AIProvider.fromName(null))
        assertEquals(AIProvider.OPENAI, AIProvider.fromName("CLAUDE"))
        assertEquals(AIProvider.GEMINI, AIProvider.fromName("GEMINI"))
    }

    @Test fun providersKeepSeparateCredentialStorage() {
        assertEquals("mural_openai_credentials", AIProvider.OPENAI.credentialPreferences)
        assertEquals("chat.mural.openai.aes", AIProvider.OPENAI.keyAlias)
        assertTrue(AIProvider.entries.map { it.credentialPreferences }.toSet().size == AIProvider.entries.size)
        assertTrue(AIProvider.entries.map { it.keyAlias }.toSet().size == AIProvider.entries.size)
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.core.AIProviderTest' -q 2>&1 | tail -15
```
Expected: compilation error, `Unresolved reference: AIProvider`.

- [ ] **Step 4: Create the enum**

Create `core/AIProvider.kt`:

```kotlin
package chat.mural.core

/** A direct AI backend the learner pays for with their own key. Storage names must never change once shipped. */
enum class AIProvider(
    val displayName: String,
    val keyURL: String,
    val usageURL: String,
    val dataURL: String,
    val voiceModelLabel: String,
    val teacherModelLabel: String,
    val credentialPreferences: String,
    val keyAlias: String,
) {
    OPENAI(
        displayName = "OpenAI",
        keyURL = "https://platform.openai.com/api-keys",
        usageURL = "https://platform.openai.com/usage",
        dataURL = "https://developers.openai.com/api/docs/guides/your-data",
        voiceModelLabel = "GPT-Live-1",
        teacherModelLabel = "GPT-5.6 Luna",
        credentialPreferences = "mural_openai_credentials",
        keyAlias = "chat.mural.openai.aes",
    ),
    GEMINI(
        displayName = "Gemini",
        keyURL = "https://aistudio.google.com/apikey",
        usageURL = "https://aistudio.google.com/usage",
        dataURL = "https://ai.google.dev/gemini-api/terms",
        voiceModelLabel = "Gemini 3.1 Flash Live",
        teacherModelLabel = "Gemini 3.8 Flash",
        credentialPreferences = "mural_gemini_credentials",
        keyAlias = "chat.mural.gemini.aes",
    );

    /** Shape check only; the provider decides whether the key is real. */
    fun isValidKey(key: String): Boolean = key.none(Char::isWhitespace) && when (this) {
        OPENAI -> key.startsWith("sk-") && key.length >= 20
        GEMINI -> key.length >= 30 && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    companion object {
        fun fromName(name: String?): AIProvider = entries.firstOrNull { it.name == name } ?: OPENAI
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.core.AIProviderTest' -q 2>&1 | tail -5
```
Expected: no output (all pass).

- [ ] **Step 6: Make `CredentialStore` provider-aware**

In `network/CredentialStore.kt`:

Replace the class header and public constructor:
```kotlin
/** Stores one provider's API key encrypted by a non-exportable Android Keystore key. */
class CredentialStore internal constructor(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
    private val isValid: (String) -> Boolean,
) {
    constructor(context: Context, provider: AIProvider = AIProvider.OPENAI) :
        this(context, provider.credentialPreferences, provider.keyAlias, provider::isValidKey)
```
Add `import chat.mural.core.AIProvider`.

In `save`, replace
```kotlin
        if (!value.startsWith("sk-") || value.length < 20 || value.any(Char::isWhitespace)) {
            throw CredentialException.Invalid
        }
```
with
```kotlin
        if (!isValid(value)) throw CredentialException.Invalid
```

In `read`, replace
```kotlin
                .takeIf { it.startsWith("sk-") && it.length >= 20 && it.none(Char::isWhitespace) }
```
with
```kotlin
                .takeIf(isValid)
```

Change the `Invalid` message to be provider-neutral (the UI uses a string resource anyway):
```kotlin
        data object Invalid : CredentialException("Enter a valid API key.")
```

Delete the now-unused constants `PREFERENCES` and `KEY_ALIAS` from the companion object (keep `CIPHERTEXT`, `IV`, `ANDROID_KEY_STORE`, `TRANSFORMATION`, `GCM_TAG_BITS`).

- [ ] **Step 7: Persist the provider choice**

In `core/ConversationProviderStore.kt`, add inside the class:
```kotlin
    suspend fun readAIProvider(): AIProvider = withContext(Dispatchers.IO) {
        AIProvider.fromName(preferences.getString("ai_provider", null))
    }
    suspend fun selectAIProvider(provider: AIProvider) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("ai_provider", provider.name).commit())
    }
```

- [ ] **Step 8: Compile**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:compileDebugKotlin -q 2>&1 | grep -E 'error|warning: unused' | head
```
Expected: no errors. (`MuralViewModel` still calls `CredentialStore(application)`, which resolves to the OpenAI default.)

- [ ] **Step 9: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/core/AIProvider.kt apps/android/app/src/main/java/chat/mural/core/ConversationProviderStore.kt apps/android/app/src/main/java/chat/mural/network/CredentialStore.kt apps/android/app/src/test/java/chat/mural/core/AIProviderTest.kt
git commit -m "Add AIProvider with per-provider credential storage"
```

---

### Task 2: Shared JSON HTTP helpers and the Gemini text client

**Files:**
- Create: `network/JsonHttp.kt`
- Modify: `network/APIClient.kt`
- Modify: `network/TeachingResponse.kt` (`isSafeSourceUrl` → `internal`)
- Create: `network/GeminiResponse.kt`
- Create: `network/GeminiTeachingClient.kt`
- Test: `apps/android/app/src/test/java/chat/mural/network/GeminiTeachingClientTest.kt`

**Interfaces:**
- Consumes: `TeachingClient`, `APIResult`, `APIUsage`, `APIClient.APIException`, `SourceLink` (existing).
- Produces: `class GeminiTeachingClient : TeachingClient` with `constructor(credentials: CredentialStore)` and `internal constructor(readCredential: () -> String?, client: OkHttpClient, baseUrl: HttpUrl)`; `internal fun decodeGeminiResponse(response: JsonObject): APIResult`; `internal suspend fun OkHttpClient.postJson(request: Request): JsonObject`; `internal fun Response.readBoundedBody(): String`; `internal val JSON_MEDIA_TYPE`.

- [ ] **Step 1: Write the failing tests**

Create `apps/android/app/src/test/java/chat/mural/network/GeminiTeachingClientTest.kt`:

```kotlin
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
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/other")))
        try { api.respond("p", "q"); fail("accepted redirect") } catch (e: APIClient.APIException.Http) { assertEquals(302, e.status) }
        assertEquals(1, server.requestCount)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.GeminiTeachingClientTest' -q 2>&1 | tail -5
```
Expected: `Unresolved reference: GeminiTeachingClient`.

- [ ] **Step 3: Extract the HTTP helpers**

Create `network/JsonHttp.kt`:

```kotlin
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
```

In `network/APIClient.kt`:
- Replace the whole body of `post` after the `request` builder (the `// Parse on OkHttp's worker...` comment through the end of `suspendCancellableCoroutine { ... }`) with `return client.postJson(request)`.
- Delete the private `Response.readBoundedBody()` function.
- Delete from the companion: `JSON_MEDIA_TYPE`, `MAX_RESPONSE_BYTES`, `JSON`. Keep `API_BASE_URL`, `VALID_PATH`, `defaultClient()`.
- Delete now-unused imports: `java.io.IOException` stays (used by `APIException`); remove `kotlin.coroutines.resume`, `kotlin.coroutines.resumeWithException`, `kotlinx.coroutines.suspendCancellableCoroutine`, `kotlinx.serialization.json.Json`, `kotlinx.serialization.json.jsonObject`, `okhttp3.Call`, `okhttp3.Callback`, `okhttp3.MediaType.Companion.toMediaType`, `okhttp3.Response`, `okio.Buffer`.
- Make the exception texts provider-neutral (the UI uses resources; these are fallbacks):
```kotlin
        data object MissingKey : APIException("Add your API key in Settings to begin.")
        data object InvalidResponse : APIException("The provider returned an incomplete response. Please try again.")
        data object Incomplete : APIException("The provider returned an incomplete response. Please try again.")
        data object Refused : APIException("Mural couldn't complete that request. Try a different topic.")
        class Http(val status: Int) : APIException(messageFor(status))

        companion object {
            private fun messageFor(status: Int): String = when (status) {
                401 -> "Your API key wasn't accepted. Check it in Settings."
                403, 404 -> "This API key may not have access to the requested model."
                429 -> "The provider's usage or rate limit was reached. Check your billing and limits."
                else -> "The provider couldn't complete the request (HTTP $status). Please try again."
            }
        }
```

In `network/TeachingResponse.kt`, change `private fun isSafeSourceUrl` to `internal fun isSafeSourceUrl`.

- [ ] **Step 4: Verify the OpenAI client still passes its tests**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.APIClientTest' -q 2>&1 | tail -5
```
Expected: no output.

- [ ] **Step 5: Gemini response decoder**

Create `network/GeminiResponse.kt`:

```kotlin
package chat.mural.network

import chat.mural.core.SourceLink
import kotlinx.serialization.json.*

/** Decodes a generateContent response into text, safe grounding sources and token usage. */
internal fun decodeGeminiResponse(response: JsonObject): APIResult {
    if ((response["promptFeedback"] as? JsonObject)?.get("blockReason") != null) throw APIClient.APIException.Refused
    val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject ?: throw APIClient.APIException.Incomplete
    when ((candidate["finishReason"] as? JsonPrimitive)?.contentOrNull) {
        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION" -> throw APIClient.APIException.Refused
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
```

- [ ] **Step 6: Gemini text client**

Create `network/GeminiTeachingClient.kt`:

```kotlin
package chat.mural.network

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Text helpers over Gemini generateContent; the same contract APIClient fulfils for OpenAI. */
class GeminiTeachingClient internal constructor(
    private val readCredential: () -> String?,
    private val client: OkHttpClient = defaultClient(),
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

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .build()
    }
}
```

- [ ] **Step 7: Run both client test classes**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.GeminiTeachingClientTest' --tests 'chat.mural.network.APIClientTest' -q 2>&1 | tail -15
```
Expected: no output. If the path assertion fails because OkHttp percent-encoded the colon, change the URL line to `.url(baseUrl.toString() + "models/$TEACHER_MODEL:generateContent")` and re-run.

- [ ] **Step 8: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/network/JsonHttp.kt apps/android/app/src/main/java/chat/mural/network/APIClient.kt apps/android/app/src/main/java/chat/mural/network/TeachingResponse.kt apps/android/app/src/main/java/chat/mural/network/GeminiResponse.kt apps/android/app/src/main/java/chat/mural/network/GeminiTeachingClient.kt apps/android/app/src/test/java/chat/mural/network/GeminiTeachingClientTest.kt
git commit -m "Add Gemini teaching client over generateContent"
```

---

### Task 3: `VoiceTransport` interface

**Files:**
- Create: `network/VoiceTransport.kt`
- Modify: `network/LiveTransport.kt`

**Interfaces:**
- Produces: `interface VoiceTransport` (below). `LiveTransport : VoiceTransport`.

- [ ] **Step 1: Create the interface**

Create `network/VoiceTransport.kt`:

```kotlin
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
```

- [ ] **Step 2: Make `LiveTransport` implement it**

In `network/LiveTransport.kt`:
- Class header: `class LiveTransport(context: Context, private val scope: CoroutineScope) : VoiceTransport {` (keep the existing parameter formatting).
- Prefix with `override`: the three `var onEvent/onFailure/onLevels`, `val started`, `val isMuted`, `suspend fun connect(` (remove the default values `= JsonArray(emptyList())` and `= null` from its parameters; Kotlin forbids defaults on overrides), `fun send(`, `fun mute(`, `fun close()`, `fun disconnect()`.
- Nothing else changes.

- [ ] **Step 3: Compile and run the existing transport-related tests**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:compileDebugKotlin -q 2>&1 | grep -E '^e:' | head; ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.LiveSessionOwnershipTest' --tests 'chat.mural.network.CommunicationAudioRouteTest' -q 2>&1 | tail -5
```
Expected: no errors, no output.

- [ ] **Step 4: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/network/VoiceTransport.kt apps/android/app/src/main/java/chat/mural/network/LiveTransport.kt
git commit -m "Extract VoiceTransport interface from LiveTransport"
```

---

### Task 4: Gemini Live translator (pure)

**Files:**
- Create: `network/GeminiLiveTranslator.kt`
- Test: `apps/android/app/src/test/java/chat/mural/network/GeminiLiveTranslatorTest.kt`

**Interfaces:**
- Produces: `internal class GeminiLiveTranslator(nowMillis: () -> Long = System::currentTimeMillis)` with `setup(instructions): JsonObject`, `history(history: JsonArray): JsonObject?`, `audioChunk(pcm16k: ByteArray): JsonObject`, `inbound(message: JsonObject): Inbound`, `outbound(event: JsonObject): JsonObject?`, `usage(type: String): JsonObject`; `data class Inbound(events, audio, interrupted, goAway)`; constants `LIVE_MODEL`, `VOICE`, `CONTEXT_FUNCTION`.

- [ ] **Step 1: Write the failing tests**

Create `apps/android/app/src/test/java/chat/mural/network/GeminiLiveTranslatorTest.kt`:

```kotlin
package chat.mural.network

import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiLiveTranslatorTest {
    private var now = 10_000L
    private val translator = GeminiLiveTranslator { now }
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun type(event: JsonObject) = event["type"]!!.jsonPrimitive.content

    @Test fun setupDeclaresAudioVoiceInstructionTranscriptionsAndTheContextTool() {
        val setup = translator.setup("Teach Spanish")["setup"]!!.jsonObject
        assertEquals("models/gemini-3.1-flash-live-preview", setup["model"]!!.jsonPrimitive.content)
        val config = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", config["responseModalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("Kore", config["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        assertEquals("Teach Spanish", setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNotNull(setup["inputAudioTranscription"]); assertNotNull(setup["outputAudioTranscription"])
        val declaration = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertEquals("request_context", declaration["name"]!!.jsonPrimitive.content)
    }

    @Test fun setupCompleteStartsTheClockAndYieldsStarted() {
        val inbound = translator.inbound(json("""{"setupComplete":{}}"""))
        assertEquals(listOf("session.started"), inbound.events.map(::type))
        now += 1_500
        val transcript = translator.inbound(json("""{"serverContent":{"outputTranscription":{"text":"Hola"}}}""")).events.single()
        assertEquals("session.output_transcript.delta", type(transcript))
        assertEquals("Hola", transcript["delta"]!!.jsonPrimitive.content)
        assertEquals(0, transcript["start_ms"]!!.jsonPrimitive.int)
        assertEquals(1_500, transcript["end_ms"]!!.jsonPrimitive.int)
        now += 700
        val next = translator.inbound(json("""{"serverContent":{"inputTranscription":{"text":"sí"}}}""")).events.single()
        assertEquals("session.input_transcript.delta", type(next))
        assertEquals(1_500, next["start_ms"]!!.jsonPrimitive.int)
        assertEquals(2_200, next["end_ms"]!!.jsonPrimitive.int)
        assertNotEquals(transcript["event_id"], next["event_id"])
    }

    @Test fun modelAudioIsDecodedAndInterruptionIsFlagged() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val data = Base64.getEncoder().encodeToString(pcm)
        val inbound = translator.inbound(json("""{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$data"}},{"text":"ignored"}]}}}"""))
        assertArrayEquals(pcm, inbound.audio.single()); assertFalse(inbound.interrupted); assertTrue(inbound.events.isEmpty())
        assertTrue(translator.inbound(json("""{"serverContent":{"interrupted":true}}""")).interrupted)
    }

    @Test fun toolCallsBecomeDelegationsAndTheirRepliesBecomeFunctionResponses() {
        val inbound = translator.inbound(json("""{"toolCall":{"functionCalls":[{"id":"call-1","name":"request_context","args":{"reason":"news"}},{"id":"call-2","name":"other","args":{}}]}}"""))
        val event = inbound.events.single()
        assertEquals("session.delegation.created", type(event))
        assertEquals("call-1", event["delegation"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("client", event["delegation"]!!.jsonObject["target"]!!.jsonPrimitive.content)
        val reply = translator.outbound(buildJsonObject { put("type", "session.commentary.append"); put("delegation_id", "call-1"); put("content", "Brief text") })!!
        val response = reply["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray.single().jsonObject
        assertEquals("call-1", response["id"]!!.jsonPrimitive.content)
        assertEquals("request_context", response["name"]!!.jsonPrimitive.content)
        assertEquals("Brief text", response["response"]!!.jsonObject["brief"]!!.jsonPrimitive.content)
        assertNull(translator.outbound(buildJsonObject { put("type", "session.commentary.append"); put("delegation_id", "call-1"); put("content", "again") }))
    }

    @Test fun cancelledToolCallsAreDropped() {
        translator.inbound(json("""{"toolCall":{"functionCalls":[{"id":"call-9","name":"request_context","args":{}}]}}"""))
        val cancel = translator.inbound(json("""{"toolCallCancellation":{"ids":["call-9"]}}"""))
        assertTrue(cancel.events.isEmpty())
        assertNull(translator.outbound(buildJsonObject { put("type", "session.commentary.append"); put("delegation_id", "call-9"); put("content", "late") }))
    }

    @Test fun appCommandsBecomeUserTurnsWithTheRightCompletion() {
        val instruction = translator.outbound(buildJsonObject { put("type", "session.instructions.append"); put("content", "Greet the learner"); put("delegation_id", JsonNull) })!!
        val client = instruction["clientContent"]!!.jsonObject
        assertTrue(client["turnComplete"]!!.jsonPrimitive.boolean)
        val text = client["turns"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("Greet the learner")); assertTrue(text.startsWith("Instruction from the app"))
        val thinking = translator.outbound(buildJsonObject { put("type", "session.thinking.append"); put("content", "challenge 2/5") })!!
        assertFalse(thinking["clientContent"]!!.jsonObject["turnComplete"]!!.jsonPrimitive.boolean)
        val spoken = translator.outbound(buildJsonObject { put("type", "session.commentary.append"); put("content", "Typed reply") })!!
        assertTrue(spoken["clientContent"]!!.jsonObject["turnComplete"]!!.jsonPrimitive.boolean)
        assertNull(translator.outbound(buildJsonObject { put("type", "session.unknown.append"); put("content", "x") }))
    }

    @Test fun historyBecomesPriorTurnsWithoutRequestingAReply() {
        val history = Json.parseToJsonElement("""[{"type":"message","role":"user","content":[{"type":"input_text","text":"Hola"}]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"¡Hola! ¿Qué tal?"}]}]""").jsonArray
        val content = translator.history(history)!!["clientContent"]!!.jsonObject
        assertFalse(content["turnComplete"]!!.jsonPrimitive.boolean)
        val turns = content["turns"]!!.jsonArray
        assertEquals("user", turns[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("model", turns[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertNull(translator.history(JsonArray(emptyList())))
    }

    @Test fun audioChunksUseThe16kMimeTypeAndUsageReportsElapsedSeconds() {
        val chunk = translator.audioChunk(byteArrayOf(0, 0))["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", chunk["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AAA=", chunk["data"]!!.jsonPrimitive.content)
        translator.inbound(json("""{"setupComplete":{}}""")); now += 4_250
        val usage = translator.usage("session.closed")
        assertEquals("session.closed", type(usage))
        assertEquals(4.25, usage["usage"]!!.jsonObject["seconds"]!!.jsonPrimitive.double, 0.001)
        assertTrue(translator.inbound(json("""{"goAway":{"timeLeft":"10s"}}""")).goAway)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.GeminiLiveTranslatorTest' -q 2>&1 | tail -5
```
Expected: `Unresolved reference: GeminiLiveTranslator`.

- [ ] **Step 3: Implement the translator**

Create `network/GeminiLiveTranslator.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the tests**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.GeminiLiveTranslatorTest' -q 2>&1 | tail -15
```
Expected: no output.

- [ ] **Step 5: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/network/GeminiLiveTranslator.kt apps/android/app/src/test/java/chat/mural/network/GeminiLiveTranslatorTest.kt
git commit -m "Add Gemini Live message translator"
```

---

### Task 5: PCM audio I/O

**Files:**
- Create: `network/PcmAudioIO.kt`
- Test: `apps/android/app/src/test/java/chat/mural/network/PcmAudioIOTest.kt`

**Interfaces:**
- Produces: `internal interface PcmAudioIO { fun start(onCaptured: (ByteArray) -> Unit); fun play(pcm24k: ByteArray); fun flushPlayback(); fun stop() }`, `internal class AndroidPcmAudioIO : PcmAudioIO`, `internal fun pcm16Level(pcm: ByteArray): Double` (RMS in 0..1).

- [ ] **Step 1: Write the failing test**

Create `apps/android/app/src/test/java/chat/mural/network/PcmAudioIOTest.kt`:

```kotlin
package chat.mural.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmAudioIOTest {
    private fun samples(vararg values: Int): ByteArray {
        val out = ByteArray(values.size * 2)
        values.forEachIndexed { i, v -> out[2 * i] = (v and 0xff).toByte(); out[2 * i + 1] = ((v shr 8) and 0xff).toByte() }
        return out
    }

    @Test fun silenceIsZeroAndFullScaleIsOne() {
        assertEquals(0.0, pcm16Level(samples(0, 0, 0, 0)), 0.0)
        assertEquals(1.0, pcm16Level(samples(32767, -32768, 32767, -32768)), 0.001)
        assertEquals(0.0, pcm16Level(ByteArray(1)), 0.0)
    }

    @Test fun levelIsRootMeanSquareOfNormalizedSamples() {
        assertEquals(0.5, pcm16Level(samples(16384, -16384)), 0.001)
    }

    @Test fun captureChunkIsFortyMillisecondsOfSixteenKilohertzMono() {
        assertEquals(1_280, AndroidPcmAudioIO.CAPTURE_CHUNK_BYTES)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.PcmAudioIOTest' -q 2>&1 | tail -5
```
Expected: `Unresolved reference: pcm16Level`.

- [ ] **Step 3: Implement**

Create `network/PcmAudioIO.kt`:

```kotlin
package chat.mural.network

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Full-duplex PCM16 mono audio: 16 kHz capture callbacks and 24 kHz streamed playback. */
internal interface PcmAudioIO {
    fun start(onCaptured: (ByteArray) -> Unit)
    fun play(pcm24k: ByteArray)
    fun flushPlayback()
    fun stop()
}

/** RMS of little-endian 16-bit samples, normalized to 0..1. */
internal fun pcm16Level(pcm: ByteArray): Double {
    if (pcm.size < 2) return 0.0
    var sum = 0.0; var count = 0; var i = 0
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble() / 32768.0
        sum += sample * sample; count++; i += 2
    }
    return sqrt(sum / count)
}

internal class AndroidPcmAudioIO : PcmAudioIO {
    private val generation = AtomicInteger()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    @SuppressLint("MissingPermission") // The transport checks RECORD_AUDIO before calling start().
    override fun start(onCaptured: (ByteArray) -> Unit) {
        val id = generation.incrementAndGet()
        val minRecord = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minRecord, CAPTURE_CHUNK_BYTES * 4))
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); throw IllegalStateException("Microphone unavailable") }
        val minTrack = AudioTrack.getMinBufferSize(PLAYBACK_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(PLAYBACK_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minTrack, PLAYBACK_RATE)) // half a second of 16-bit mono
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        this.record = record; this.track = track
        record.startRecording(); track.play()
        captureThread = Thread({
            val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
            while (generation.get() == id) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) onCaptured(buffer.copyOf(read)) else if (read < 0) break
            }
        }, "mural-gemini-capture").also { it.start() }
        playbackThread = Thread({
            while (generation.get() == id) {
                val chunk = playbackQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (generation.get() == id) track.write(chunk, 0, chunk.size)
            }
        }, "mural-gemini-playback").also { it.start() }
    }

    override fun play(pcm24k: ByteArray) { if (track != null) playbackQueue.offer(pcm24k) }

    override fun flushPlayback() {
        playbackQueue.clear()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    override fun stop() {
        generation.incrementAndGet()
        playbackQueue.clear()
        captureThread?.join(500); captureThread = null
        playbackThread?.join(500); playbackThread = null
        record?.let { runCatching { it.stop() }; it.release() }; record = null
        track?.let { runCatching { it.pause(); it.flush(); it.stop() }; it.release() }; track = null
    }

    companion object {
        const val CAPTURE_RATE = 16_000
        const val PLAYBACK_RATE = 24_000
        /** 40 ms of 16-bit mono at 16 kHz. */
        const val CAPTURE_CHUNK_BYTES = CAPTURE_RATE * 2 * 40 / 1000
    }
}
```

- [ ] **Step 4: Run the test**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.PcmAudioIOTest' -q 2>&1 | tail -5
```
Expected: no output.

- [ ] **Step 5: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/network/PcmAudioIO.kt apps/android/app/src/test/java/chat/mural/network/PcmAudioIOTest.kt
git commit -m "Add PCM audio loop for the Gemini voice transport"
```

---

### Task 6: Gemini Live WebSocket wrapper

**Files:**
- Create: `network/GeminiLiveSocket.kt`
- Test: `apps/android/app/src/test/java/chat/mural/network/GeminiLiveSocketTest.kt`

**Interfaces:**
- Consumes: `GeminiLiveTranslator` (Task 4), `APIClient.APIException.Http`.
- Produces: `internal class GeminiLiveSocket(client: OkHttpClient, endpoint: HttpUrl, key: String, translator: GeminiLiveTranslator, onInbound: (GeminiLiveTranslator.Inbound) -> Unit, onClosed: () -> Unit, onFailure: (Throwable) -> Unit)` with `suspend fun open(instructions: String, timeoutMillis: Long)`, `fun send(message: JsonObject): Boolean`, `fun close()`, `fun cancel()`; `companion val LIVE_ENDPOINT: HttpUrl`.

- [ ] **Step 1: Write the failing tests**

Create `apps/android/app/src/test/java/chat/mural/network/GeminiLiveSocketTest.kt`:

```kotlin
package chat.mural.network

import kotlinx.coroutines.CompletableDeferred
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
```

- [ ] **Step 2: Run to verify they fail**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.GeminiLiveSocketTest' -q 2>&1 | tail -5
```
Expected: `Unresolved reference: GeminiLiveSocket`.

- [ ] **Step 3: Implement**

Create `network/GeminiLiveSocket.kt`:

```kotlin
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
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (finished.compareAndSet(false, true)) onClosed() }
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
```

Note: `ready.isCompleted` is true after an exceptional completion too; `send` after a failed handshake is prevented by `socket?.cancel()` in `open`, which makes `socket.send` return false.

- [ ] **Step 4: Run the tests**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest --tests 'chat.mural.network.GeminiLiveSocketTest' -q 2>&1 | tail -15
```
Expected: no output. If the scheme assertion complains that MockWebServer gave `http` while the builder forces `wss`, note OkHttp accepts `http(s)` URLs for WebSockets; the production endpoint uses `https`, which OkHttp upgrades to `wss`.

- [ ] **Step 5: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/network/GeminiLiveSocket.kt apps/android/app/src/test/java/chat/mural/network/GeminiLiveSocketTest.kt
git commit -m "Add Gemini Live WebSocket wrapper"
```

---

### Task 7: `GeminiLiveTransport`

**Files:**
- Create: `network/GeminiLiveTransport.kt`

**Interfaces:**
- Consumes: `VoiceTransport` (Task 3), `GeminiLiveTranslator` (Task 4), `PcmAudioIO`/`AndroidPcmAudioIO`/`pcm16Level` (Task 5), `GeminiLiveSocket` (Task 6), `LiveTransport.TransportException`, `selectCommunicationDevice`, `LegacyCommunicationAudioRoute` (existing in `CommunicationAudioRoute.kt`), string resources `error_transport_microphone`, `error_transport_timeout`, `error_transport_connection`, `error_transport_channel_closed`, `error_transport_audio_focus`, `error_transport_audio_interrupted`, `error_transport_audio_stopped` (existing).
- Produces: `class GeminiLiveTransport(context: Context, scope: CoroutineScope, credentials: CredentialStore) : VoiceTransport`.

No unit test: every method needs a real `Context`, `AudioManager` and audio devices, and the project has no Robolectric or mocking library. The pure pieces are covered by Tasks 4 to 6; the composition is verified on the phone in Task 10.

- [ ] **Step 1: Implement**

Create `network/GeminiLiveTransport.kt`:

```kotlin
package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import chat.mural.R
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** Real-time voice over Gemini Live: WebSocket JSON, PCM capture and playback, and the phone's call audio route. */
class GeminiLiveTransport internal constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val readCredential: () -> String?,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
    private val endpoint: HttpUrl = GeminiLiveSocket.LIVE_ENDPOINT,
    private val audioFactory: () -> PcmAudioIO = { AndroidPcmAudioIO() },
) : VoiceTransport {
    constructor(context: Context, scope: CoroutineScope, credentials: CredentialStore) : this(context, scope, credentials::read)

    override var onEvent: ((JsonObject) -> Unit)? = null
    override var onFailure: ((String) -> Unit)? = null
    override var onLevels: ((Double, Double) -> Unit)? = null
    override val started: Boolean get() = session?.started?.get() == true
    override val isMuted: Boolean get() = session?.muted?.get() == true

    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "mural-gemini-live") }.asCoroutineDispatcher()
    private val workerScope = CoroutineScope(SupervisorJob() + worker)
    @Volatile private var session: Session? = null

    private inner class Session(val audio: PcmAudioIO) {
        val translator = GeminiLiveTranslator()
        lateinit var socket: GeminiLiveSocket
        val started = AtomicBoolean(false)
        val muted = AtomicBoolean(false)
        val closing = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        var usageJob: Job? = null
        var meterJob: Job? = null
        @Volatile var inputLevel = 0.0
        @Volatile var outputLevel = 0.0
        var focusRequest: AudioFocusRequest? = null
        var previousMode = AudioManager.MODE_NORMAL
        var ownsAudioMode = false
        var ownsCommunicationRoute = false
        var legacyRoute: LegacyCommunicationAudioRoute? = null
    }

    override suspend fun connect(api: LiveSessionProvider, instructions: String, history: JsonArray, language: String?) = withContext(worker) {
        session?.let { retire(it) }
        if (applicationContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw LiveTransport.TransportException.Microphone(applicationContext.getString(R.string.error_transport_microphone))
        }
        val key = readCredential() ?: throw APIClient.APIException.MissingKey
        val current = Session(audioFactory())
        current.socket = GeminiLiveSocket(client, endpoint, key, current.translator,
            onInbound = { inbound -> workerScope.launch { receive(current, inbound) } },
            onClosed = { workerScope.launch { serverClosed(current) } },
            onFailure = { workerScope.launch { fail(current, applicationContext.getString(R.string.error_transport_channel_closed)) } })
        session = current
        try {
            acquireAudio(current)
            current.socket.open(instructions, READY_TIMEOUT_MILLISECONDS)
            current.translator.history(history)?.let { current.socket.send(it) }
            current.audio.start { chunk -> captured(current, chunk) }
            current.started.set(true)
            emit(current, buildJsonObject { put("type", "session.started") })
            current.meterJob = workerScope.launch {
                while (isActive && session === current) {
                    scope.launch { if (session === current) onLevels?.invoke(if (current.muted.get()) 0.0 else current.inputLevel, current.outputLevel) }
                    current.inputLevel *= LEVEL_DECAY; current.outputLevel *= LEVEL_DECAY
                    delay(METER_INTERVAL_MILLISECONDS)
                }
            }
            current.usageJob = workerScope.launch {
                while (isActive && session === current && !current.closing.get()) {
                    delay(USAGE_INTERVAL_MILLISECONDS)
                    if (session === current && !current.closing.get()) emit(current, current.translator.usage("session.usage.updated"))
                }
            }
        } catch (_: TimeoutCancellationException) {
            retire(current); throw LiveTransport.TransportException.Timeout(applicationContext.getString(R.string.error_transport_timeout))
        } catch (error: CancellationException) {
            retire(current); throw error
        } catch (error: APIClient.APIException) {
            retire(current); throw error
        } catch (error: LiveTransport.TransportException) {
            retire(current); throw error
        } catch (_: Throwable) {
            retire(current); throw LiveTransport.TransportException.Connection(applicationContext.getString(R.string.error_transport_connection))
        }
    }

    override fun send(event: JsonObject): Boolean {
        val current = session ?: return false
        if (!current.started.get() || current.closing.get()) return false
        val message = current.translator.outbound(event) ?: return true
        return current.socket.send(message)
    }

    override fun mute(muted: Boolean) { session?.muted?.set(muted) }

    /** Ends the session: stops audio, reports final seconds as session.closed, closes the socket. */
    override fun close() {
        val current = session ?: return
        if (!current.closing.compareAndSet(false, true)) return
        workerScope.launch {
            runCatching { current.audio.stop() }
            val closed = current.translator.usage("session.closed")
            current.socket.close()
            retire(current)
            scope.launch { onEvent?.invoke(closed) }
        }
    }

    override fun disconnect() {
        val current = session ?: return
        current.closing.set(true)
        session = null
        workerScope.launch { current.socket.cancel(); retire(current) }
    }

    private fun captured(current: Session, chunk: ByteArray) {
        if (session !== current || current.closing.get()) return
        current.inputLevel = maxOf(current.inputLevel, minOf(1.0, pcm16Level(chunk) * LEVEL_GAIN))
        if (current.muted.get()) return
        current.socket.send(current.translator.audioChunk(chunk))
    }

    private fun receive(current: Session, inbound: GeminiLiveTranslator.Inbound) {
        if (session !== current || current.closing.get()) return
        if (inbound.interrupted) current.audio.flushPlayback()
        for (pcm in inbound.audio) {
            current.audio.play(pcm)
            current.outputLevel = maxOf(current.outputLevel, minOf(1.0, pcm16Level(pcm) * LEVEL_GAIN))
        }
        inbound.events.forEach { emit(current, it) }
        if (inbound.goAway) close()
    }

    private fun serverClosed(current: Session) {
        if (session !== current || !current.closing.compareAndSet(false, true)) return
        runCatching { current.audio.stop() }
        val closed = current.translator.usage("session.closed")
        retire(current)
        scope.launch { onEvent?.invoke(closed) }
    }

    private fun fail(current: Session, message: String) {
        if (session !== current || current.closing.get() || !current.failed.compareAndSet(false, true)) return
        retire(current)
        scope.launch { onFailure?.invoke(message) }
    }

    private fun emit(current: Session, event: JsonObject) {
        scope.launch { if (session === current) onEvent?.invoke(event) }
    }

    /** Releases everything the session owns; safe to call more than once. */
    private fun retire(current: Session) {
        if (session === current) session = null
        current.usageJob?.cancel(); current.meterJob?.cancel()
        runCatching { current.audio.stop() }
        if (current::socket.isInitialized) current.socket.cancel()
        releaseAudio(current)
        scope.launch { onLevels?.invoke(0.0, 0.0) }
    }

    // ponytail: audio focus and routing duplicated from LiveTransport; extract a shared VoiceAudioSession once the WebRTC path can be re-tested on a device.
    private fun acquireAudio(current: Session) {
        current.previousMode = audioManager.mode
        if (current.previousMode != AudioManager.MODE_NORMAL ||
            (Build.VERSION.SDK_INT < 31 && LegacyCommunicationAudioRoute.hasExistingSco(applicationContext, audioManager))) {
            throw LiveTransport.TransportException.AudioFocus(applicationContext.getString(R.string.error_transport_audio_focus))
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    workerScope.launch { fail(current, applicationContext.getString(R.string.error_transport_audio_interrupted)) }
                }
            }, Handler(Looper.getMainLooper()))
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw LiveTransport.TransportException.AudioFocus(applicationContext.getString(R.string.error_transport_audio_focus))
        }
        current.focusRequest = request
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        current.ownsAudioMode = true
        if (Build.VERSION.SDK_INT >= 31) {
            val selected = selectCommunicationDevice(audioManager.communicationDevice, audioManager.availableCommunicationDevices,
                sameDevice = { left, right -> left.id == right.id }) { it.type }
            if (selected != null && audioManager.communicationDevice?.id != selected.id && audioManager.setCommunicationDevice(selected)) {
                current.ownsCommunicationRoute = true
            }
        } else {
            @Suppress("DEPRECATION") val previousSpeakerphone = audioManager.isSpeakerphoneOn
            current.legacyRoute = LegacyCommunicationAudioRoute(applicationContext, audioManager, workerScope, previousSpeakerphone) {
                workerScope.launch { fail(current, applicationContext.getString(R.string.error_transport_audio_stopped)) }
            }.also { it.start() }
        }
    }

    private fun releaseAudio(current: Session) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) { if (current.ownsCommunicationRoute) audioManager.clearCommunicationDevice() }
            else current.legacyRoute?.close(restoreSpeakerphone = true)
        }
        current.legacyRoute = null; current.ownsCommunicationRoute = false
        if (current.ownsAudioMode) { runCatching { audioManager.mode = current.previousMode }; current.ownsAudioMode = false }
        current.focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        current.focusRequest = null
    }

    companion object {
        private const val READY_TIMEOUT_MILLISECONDS = 20_000L
        private const val USAGE_INTERVAL_MILLISECONDS = 5_000L
        private const val METER_INTERVAL_MILLISECONDS = 100L
        private const val LEVEL_GAIN = 4.0
        private const val LEVEL_DECAY = 0.6
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:compileDebugKotlin -q 2>&1 | grep -E '^e:' | head
```
Expected: no errors. Common fixes if any: `LegacyCommunicationAudioRoute`'s last parameter is named `onFailure` (pass it as a trailing lambda as written); `selectCommunicationDevice` is `internal` in the same package.

- [ ] **Step 3: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/network/GeminiLiveTransport.kt
git commit -m "Add Gemini Live voice transport"
```

---

### Task 8: View model wiring

**Files:**
- Modify: `MuralViewModel.kt`

**Interfaces:**
- Consumes: `AIProvider`, `CredentialStore(context, provider)`, `ConversationProviderStore.readAIProvider/selectAIProvider` (Task 1), `GeminiTeachingClient` (Task 2), `VoiceTransport` (Task 3), `GeminiLiveTransport` (Task 7).
- Produces: `var aiProvider: AIProvider` (observable, private set) and `fun selectAIProvider(provider: AIProvider)` for the UI.

- [ ] **Step 1: Replace the fixed clients with provider-selected ones**

Replace lines 78 to 80
```kotlin
    private val credentials = CredentialStore(application)
    private val api = APIClient(credentials)
    private val transport = LiveTransport(application, viewModelScope)
```
with
```kotlin
    var aiProvider by mutableStateOf(AIProvider.OPENAI); private set
    private val credentialStores = AIProvider.entries.associateWith { CredentialStore(application, it) }
    private val credentials: CredentialStore get() = credentialStores.getValue(aiProvider)
    private val openAI = APIClient(credentialStores.getValue(AIProvider.OPENAI))
    private val gemini = GeminiTeachingClient(credentialStores.getValue(AIProvider.GEMINI))
    private val api: TeachingClient get() = if (aiProvider == AIProvider.GEMINI) gemini else openAI
    private val webrtcTransport = LiveTransport(application, viewModelScope)
    private val geminiTransport = GeminiLiveTransport(application, viewModelScope, credentialStores.getValue(AIProvider.GEMINI))
    /** The transport of the running or last conversation; chosen when a voice session starts. */
    private var transport: VoiceTransport = webrtcTransport
```

- [ ] **Step 2: Load the stored provider before reading the key**

In `init`, replace
```kotlin
                val loaded = withContext(Dispatchers.IO) { repository.load() to credentials.hasKey }
                archive = loaded.first.archive
                val providers = providerStore.read(if (loaded.second) ConversationProvider.PERSONAL_KEY else ConversationProvider.HOSTED_MINUTES)
```
with
```kotlin
                val loaded = withContext(Dispatchers.IO) {
                    val stored = repository.load()
                    val provider = providerStore.readAIProvider()
                    Triple(stored, provider, credentialStores.getValue(provider).hasKey)
                }
                archive = loaded.first.archive
                aiProvider = loaded.second
                val providers = providerStore.read(if (loaded.third) ConversationProvider.PERSONAL_KEY else ConversationProvider.HOSTED_MINUTES)
```
Then replace `conversationProvider = providers.selection` with
```kotlin
                conversationProvider = if (aiProvider == AIProvider.GEMINI) ConversationProvider.PERSONAL_KEY else providers.selection
```
and `hasKey = loaded.second` with `hasKey = loaded.third`.

- [ ] **Step 3: Wire callbacks on both transports**

Replace
```kotlin
        transport.onEvent = { event ->
            try { handle(event) }
            catch (_: IllegalArgumentException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
            catch (_: IllegalStateException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
        }
        transport.onFailure = { fail(it) }
        transport.onLevels = { input, output ->
            inputLevel = input; outputLevel = output
            if (input > 0.03 || output > 0.03) lastActivity = nowSeconds()
        }
```
with
```kotlin
        for (voice in listOf<VoiceTransport>(webrtcTransport, geminiTransport)) {
            voice.onEvent = { event ->
                if (voice === transport) {
                    try { handle(event) }
                    catch (_: IllegalArgumentException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
                    catch (_: IllegalStateException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
                }
            }
            voice.onFailure = { if (voice === transport) fail(it) }
            voice.onLevels = { input, output ->
                if (voice === transport) {
                    inputLevel = input; outputLevel = output
                    if (input > 0.03 || output > 0.03) lastActivity = nowSeconds()
                }
            }
        }
```
Only the transport chosen for the current conversation may drive state; the other stays wired but silent.

- [ ] **Step 4: Provider-named error messages**

Replace `resolveMessage`:
```kotlin
    private fun resolveMessage(e: Throwable, @StringRes fallback: Int): String {
        val app = getApplication<Application>()
        val res = errorMessageRes(e)
        val provider = aiProvider.displayName
        return when {
            res == R.string.error_http_generic && e is APIClient.APIException.Http -> app.getString(res, provider, e.status)
            e is HostedFailure -> hostedMessage(e)
            res in PROVIDER_NAMED_MESSAGES -> app.getString(res, provider)
            res != 0 -> app.getString(res)
            e is LiveTransport.TransportException -> e.message ?: app.getString(fallback)
            else -> app.getString(fallback)
        }
    }
```
Add at the bottom of the class (or a companion object if one exists; otherwise create one):
```kotlin
    companion object {
        private val PROVIDER_NAMED_MESSAGES = setOf(R.string.error_missing_key, R.string.error_key_invalid,
            R.string.error_incomplete_response, R.string.error_http_401, R.string.error_http_403_404, R.string.error_http_429)
    }
```
Update the two direct uses of `error_missing_key` and the one of `error_accept_ai_consent`:
- line ~304: `getString(R.string.error_accept_ai_consent, aiProvider.displayName)`
- line ~308 and ~634: `getString(R.string.error_missing_key, aiProvider.displayName)`
Run `grep -n 'R.string.error_missing_key\|R.string.error_accept_ai_consent' apps/android/app/src/main/java/chat/mural/MuralViewModel.kt` and make sure every `getString(` call for those two passes the provider name (the `errorMessageRes` mapping line stays as is).

- [ ] **Step 5: Provider selection**

Add after `selectConversationProvider`:
```kotlin
    fun selectAIProvider(provider: AIProvider) {
        if (isRunning || !storageReady || provider == aiProvider) return
        generation++; actionJob?.cancel(); assessmentJob?.cancel(); languageCheckJob?.cancel(); meanings.reset(); working = false
        if (session != null) resetConversation()
        aiProvider = provider
        hasKey = credentials.hasKey
        if (provider == AIProvider.GEMINI && conversationProvider == ConversationProvider.HOSTED_MINUTES) {
            selectConversationProvider(ConversationProvider.PERSONAL_KEY)
        }
        viewModelScope.launch {
            try { providerStore.selectAIProvider(provider) }
            catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.provider_preference_save_failed)) }
        }
        recoverFinalAssessments()
    }
```
`saveKey`/`deleteKey` need no change: `credentials` now resolves to the active provider's store.

- [ ] **Step 6: Pick the transport when a voice session starts**

In `startVoice` (the function containing `newSession(true); state = "connecting"`), insert before `newSession(true)`:
```kotlin
        transport = if (choice == ConversationProvider.HOSTED_MINUTES || aiProvider == AIProvider.OPENAI) webrtcTransport else geminiTransport
```
Replace `val provider: LiveSessionProvider = if (choice == ConversationProvider.PERSONAL_KEY) api else {` with
`val provider: LiveSessionProvider = if (choice == ConversationProvider.PERSONAL_KEY) openAI else {`.

In `onCleared`, replace `transport.disconnect()` with `webrtcTransport.disconnect(); geminiTransport.disconnect()`.

- [ ] **Step 7: Compile and run the view model tests**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:compileDebugKotlin -q 2>&1 | grep -E '^e:' | head -20; ./gradlew :app:testDebugUnitTest --tests 'chat.mural.MuralViewModelTest' -q 2>&1 | tail -5
```
Expected: no errors, no output. Compilation of `SettingsScreen.kt` still succeeds because nothing it uses was removed.

- [ ] **Step 8: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/java/chat/mural/MuralViewModel.kt
git commit -m "Select text client and voice transport by AI provider"
```

---

### Task 9: Strings, Settings and consent UI

**Files:**
- Modify: `apps/android/app/src/main/res/values/strings.xml`
- Modify: `apps/android/app/src/main/res/values-es/strings.xml`
- Modify: `apps/android/app/src/main/res/values/settings_parity.xml`
- Modify: `apps/android/app/src/main/res/values-es/settings_parity.xml`
- Modify: `ui/SettingsScreen.kt`
- Modify: `ui/Onboarding.kt`
- Modify: `ui/MuralApp.kt`
- Modify: `apps/android/app/src/androidTest/java/chat/mural/SettingsDetailsTest.kt`, `apps/android/app/src/androidTest/java/chat/mural/SettingsParityTest.kt`

**Interfaces:**
- Consumes: `vm.aiProvider`, `vm.selectAIProvider` (Task 8), `AIProvider` fields (Task 1).

- [ ] **Step 1: English strings**

In `res/values/strings.xml` change these values (names unchanged unless noted):

```xml
<string name="error_accept_ai_consent">Accept %1$s use in Settings before you begin.</string>
<string name="error_missing_key">Add your %1$s key in Settings to begin.</string>
<string name="error_key_invalid">Enter a valid %1$s API key.</string>
<string name="error_incomplete_response">%1$s returned an incomplete response. Please try again.</string>
<string name="error_http_401">Your %1$s key wasn\'t accepted. Check it in Settings.</string>
<string name="error_http_403_404">This API key may not have access to the requested model. Check your %1$s project.</string>
<string name="error_http_429">%1$s\'s usage or rate limit was reached. Check your project billing and limits.</string>
<string name="error_http_generic">%1$s couldn\'t complete the request (HTTP %2$d). Please try again.</string>
<string name="settings_usage_billing_link">%1$s usage and billing</string>
<string name="settings_ai_permission_summary">With permission, Mural sends audio and the text you choose to %1$s. You can use your history and words without granting it.</string>
<string name="settings_data_use_footer">Audio and selected text go to %1$s while you practise. Requests disable provider storage where supported; abuse-monitoring retention may still apply. Raw audio is not saved by Mural.</string>
<string name="settings_open_api_keys">Open %1$s API keys</string>
<string name="settings_key_dialog_title">%1$s API key</string>
<string name="settings_models_footer">Voice: %1$s · Teacher: %2$s</string>
<string name="consent_ai_summary">With your permission, Mural sends audio and selected text to %1$s to provide conversations and meanings. Provider retention rules apply.</string>
```
Rename `settings_openai_data_controls` to `settings_provider_data_controls` with value `%1$s data controls`.
Delete `settings_section_openai_account` (unused).
Add:
```xml
<string name="settings_ai_provider">AI provider</string>
<string name="settings_usage_footer_gemini">Voice time is recorded on this phone. Gemini usage, free-tier limits and billing are shown in Google AI Studio. The time limit is local, not a billing cap.</string>
```

- [ ] **Step 2: Spanish strings**

In `res/values-es/strings.xml`:

```xml
<string name="error_accept_ai_consent">Acepta el uso de %1$s en Ajustes antes de empezar.</string>
<string name="error_missing_key">Añade tu clave de %1$s en Ajustes para empezar.</string>
<string name="error_key_invalid">Introduce una clave de la API de %1$s válida.</string>
<string name="error_incomplete_response">%1$s devolvió una respuesta incompleta. Inténtalo de nuevo.</string>
<string name="error_http_401">Tu clave de %1$s no fue aceptada. Revísala en Ajustes.</string>
<string name="error_http_403_404">Esta clave de API puede no tener acceso al modelo solicitado. Revisa tu proyecto de %1$s.</string>
<string name="error_http_429">Se alcanzó el límite de uso o de solicitudes de %1$s. Revisa la facturación y los límites de tu proyecto.</string>
<string name="error_http_generic">%1$s no pudo completar la solicitud (HTTP %2$d). Inténtalo de nuevo.</string>
<string name="notice_voice_update_rejected">Se rechazó una actualización de voz. Si Mural deja de responder, termina esta conversación y empieza otra.</string>
<string name="settings_usage_billing_link">Uso y facturación de %1$s</string>
<string name="settings_ai_permission_summary">Con permiso, Mural envía audio y el texto elegido a %1$s. Puedes usar el historial y las palabras sin concederlo.</string>
<string name="settings_provider_data_controls">Controles de datos de %1$s</string>
<string name="settings_data_use_footer">El audio y el texto elegido se envían a %1$s durante la práctica. Mural no guarda audio sin procesar.</string>
<string name="settings_open_api_keys">Abrir las claves de API de %1$s</string>
<string name="settings_key_dialog_title">Clave de %1$s</string>
<string name="consent_ai_summary">Con tu permiso, Mural envía audio y el texto que elijas a %1$s para ofrecer conversaciones, explicaciones y temas actuales.</string>
<string name="settings_ai_provider">Proveedor de IA</string>
<string name="settings_usage_footer_gemini">El tiempo de voz se registra en este teléfono. El uso, los límites gratuitos y la facturación de Gemini se ven en Google AI Studio. La duración orientativa es local, no un tope de gasto.</string>
```
Delete `settings_section_openai_account`. If the Spanish file has `settings_models_footer`, set it to `Voz: %1$s · Profesor: %2$s`; otherwise the English default applies.

- [ ] **Step 3: Parity footers (both languages)**

`res/values/settings_parity.xml`:
```xml
<string name="settings_byok_version_footer">An optional way to use Mural. %1$s bills usage on your own key directly to you.</string>
<string name="settings_key_owner_footer">Your %1$s account pays for usage. The key stays encrypted on this phone and is sent only to %1$s.</string>
```
`res/values-es/settings_parity.xml`:
```xml
<string name="settings_byok_version_footer">Una forma opcional de usar Mural. %1$s te cobra directamente el uso de tu propia clave.</string>
<string name="settings_key_owner_footer">El uso se factura a tu cuenta de %1$s. La clave se guarda cifrada en este teléfono y solo se envía a %1$s.</string>
```

- [ ] **Step 4: Settings screen**

In `ui/SettingsScreen.kt` add `import chat.mural.core.AIProvider`, then:

Account row: change `if (onAccount != null) item {` to `if (onAccount != null && vm.aiProvider == AIProvider.OPENAI) item {`.

Advanced group: replace the `item { SettingsGroup(stringResource(R.string.settings_advanced), ...` block's opening and the key rows with:
```kotlin
            item {
                val provider = vm.aiProvider
                SettingsGroup(stringResource(R.string.settings_advanced),
                    if (!vm.hasKey) stringResource(R.string.settings_byok_version_footer, provider.displayName) else null) {
                    SettingsChoiceRow(stringResource(R.string.settings_ai_provider), provider.displayName, provider.name,
                        AIProvider.entries.map { it.name to it.displayName }, "settings-ai-provider", !vm.isRunning) {
                        vm.selectAIProvider(AIProvider.valueOf(it))
                    }
                    SettingsDivider()
                    SettingsRow(stringResource(R.string.settings_use_own_key), symbol = SettingsSymbol.KEY,
                        chevron = !advanced, modifier = Modifier.testTag("advanced-api-key"), onClick = { advanced = !advanced })
```
Inside the `AnimatedVisibility` column:
- `SettingsRow(stringResource(R.string.settings_open_api_keys, provider.displayName), tint = MuralColors.Secondary, onClick = { open(provider.keyURL) })`
- `Text(stringResource(R.string.settings_key_owner_footer, provider.displayName), ...)`

Usage group:
```kotlin
            item {
                val usage = UsageSummary.of(vm.archive.sessions)
                val provider = vm.aiProvider
                SettingsGroup(stringResource(R.string.settings_keep_comfortable),
                    stringResource(if (provider == AIProvider.OPENAI) R.string.settings_usage_footer else R.string.settings_usage_footer_gemini)) {
```
Wrap the voice estimate row:
```kotlin
                    if (provider == AIProvider.OPENAI) {
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_voice_estimate_label), usage.voiceEstimate)
                    }
```
(remove the `SettingsDivider()` that preceded it so dividers stay balanced) and change the billing row to
`SettingsRow(stringResource(R.string.settings_usage_billing_link, provider.displayName), tint = MuralColors.Secondary, onClick = { open(provider.usageURL) })`.

Version/footer group:
- `Text(stringResource(R.string.settings_models_footer, vm.aiProvider.voiceModelLabel, vm.aiProvider.teacherModelLabel), ...)`
- `SettingsRow(stringResource(R.string.settings_provider_data_controls, vm.aiProvider.displayName), tint = MuralColors.Secondary, onClick = { open(vm.aiProvider.dataURL) })`
- `Text(stringResource(R.string.settings_data_use_footer, vm.aiProvider.displayName), ...)`

Permission dialog (around line 223): `Text(stringResource(R.string.settings_ai_permission_summary, vm.aiProvider.displayName), color = MuralColors.Secondary)`.

`KeyDialog`: `Text(stringResource(R.string.settings_key_dialog_title, vm.aiProvider.displayName), style = MaterialTheme.typography.headlineMedium)`.

- [ ] **Step 5: Consent dialog**

In `ui/Onboarding.kt` change the signature to `fun AIConsentDialog(providerName: String, onAgree: () -> Unit, onDecline: () -> Unit)` and the summary to `stringResource(R.string.consent_ai_summary, providerName)`.

In `ui/MuralApp.kt` change the call to
```kotlin
            if (showConsent) AIConsentDialog(
                providerName = vm.aiProvider.displayName,
                onAgree = {
```

- [ ] **Step 6: Instrumented test expectations**

`androidTest/java/chat/mural/SettingsDetailsTest.kt` (around lines 51 to 52):
```kotlin
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_open_api_keys, "OpenAI"), substring = true))
        settings.performScrollToNode(hasText(activity.getString(R.string.settings_models_footer, "GPT-Live-1", "GPT-5.6 Luna")))
```
`androidTest/java/chat/mural/SettingsParityTest.kt` (lines 65 and 82): `getString(R.string.settings_key_owner_footer, "OpenAI")`.
Run `grep -rn 'settings_byok_version_footer\|consent_ai_summary\|settings_key_dialog_title\|error_missing_key' apps/android/app/src/androidTest` and add `"OpenAI"` to any other `getString` of a changed resource.

- [ ] **Step 7: Build, lint and unit tests**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -q 2>&1 | grep -vE '^\s*$' | tail -30
```
Expected: no errors. Lint will flag any format string still read without arguments (`StringFormatInvalid`/`StringFormatMatches`); fix the call site rather than suppressing.

```bash
cd /Users/drudilea/Repos/mural && python3 scripts/check_cross_platform.py && echo PARITY_OK
```
Expected: `PARITY_OK`.

- [ ] **Step 8: Commit**

```bash
cd /Users/drudilea/Repos/mural
git add apps/android/app/src/main/res/values/strings.xml apps/android/app/src/main/res/values-es/strings.xml apps/android/app/src/main/res/values/settings_parity.xml apps/android/app/src/main/res/values-es/settings_parity.xml apps/android/app/src/main/java/chat/mural/ui/SettingsScreen.kt apps/android/app/src/main/java/chat/mural/ui/Onboarding.kt apps/android/app/src/main/java/chat/mural/ui/MuralApp.kt apps/android/app/src/androidTest/java/chat/mural/SettingsDetailsTest.kt apps/android/app/src/androidTest/java/chat/mural/SettingsParityTest.kt
git commit -m "Add AI provider selector and provider-named settings text"
```

---

### Task 10: Install and verify on the phone

**Files:** none (verification). The phone is an OPPO CPH1951 on Android 11 (legacy audio route path, SDK < 31).

- [ ] **Step 1: Install**

```bash
cd /Users/drudilea/Repos/mural/apps/android
~/Library/Android/sdk/platform-tools/adb devices -l
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n chat.mural.android/chat.mural.MainActivity
```
Expected: `Success`. Existing data survives (`-r`).

- [ ] **Step 2: Manual checklist (the user does these on the phone; watch logcat alongside)**

```bash
~/Library/Android/sdk/platform-tools/adb logcat -v time chat.mural:V AndroidRuntime:E *:S
```

1. Settings → Advanced: the "AI provider" row shows OpenAI. Switch to Gemini. The account row disappears, the voice estimate row disappears, the footer talks about Google AI Studio, the models footer reads "Voice: Gemini 3.1 Flash Live · Teacher: Gemini 3.8 Flash".
2. "Open Gemini API keys" opens Google AI Studio. If "Gemini usage and billing" lands on a 404, change `AIProvider.GEMINI.usageURL` to the API keys page and rebuild.
3. Save a Gemini key in the dialog titled "Gemini API key". A key with a space is rejected with the Gemini-named error.
4. Accept the AI consent, which now names Gemini. Start a voice conversation in Spanish: greeting is heard within a few seconds, the orb reacts to both voices, transcripts appear for both speakers.
5. Interrupt the model mid-sentence: playback stops promptly and the model responds to the interruption.
6. Type a reply with "Type instead": the model speaks a follow-up.
7. Ask about a current event: a topic brief with sources appears in Topics; the voice mentions it. Logcat shows no crash.
8. End the conversation: Settings shows increased voice time; no cost estimate.
9. Let a conversation run past 15 minutes (or set the limit to 5 minutes and check that the local limit ends it first): the conversation ends cleanly with history saved.
10. Switch back to OpenAI: the account row and voice estimate return; if an OpenAI key was saved earlier, "Your key is saved securely" still shows.

- [ ] **Step 3: Instrumented tests on the phone (optional, installs a separate test app)**

```bash
cd /Users/drudilea/Repos/mural/apps/android && ./gradlew :app:connectedUiTestAndroidTest -q 2>&1 | tail -20
```
Expected: pass. These do not need an API key.

- [ ] **Step 4: Record results**

Append a short "Gemini provider (Android)" section to `verification/android-validation.md` listing which checklist items passed, the phone model and Android version, and the date. Commit:
```bash
cd /Users/drudilea/Repos/mural
git add verification/android-validation.md
git commit -m "Record Gemini provider checks on Android 11"
```

---

## Self-review notes

- Spec coverage: provider selection and per-provider keys (Task 1, 8, 9); text client with schema, search, errors (Task 2); `VoiceTransport` and Gemini transport with clock, mapping tables, delegation via `request_context`, goAway, usage events (Tasks 3 to 7); settings, strings, hidden OpenAI-only rows, consent (Task 9); tests (Tasks 1, 2, 4, 5, 6) and the manual device checklist (Task 10). Deviations from the spec, both decided here: provider choice is stored in `ConversationProviderStore` instead of `Preferences` (parity script), and the selector is disabled during a conversation instead of ending it (matches every other setting row).
- Not covered by unit tests: `GeminiLiveTransport` composition and `AndroidPcmAudioIO` (need a device), `CredentialStore` per-provider aliases (Android Keystore). All three are exercised by Task 10.
- Name consistency: `AIProvider.displayName/keyURL/usageURL/dataURL/voiceModelLabel/teacherModelLabel/credentialPreferences/keyAlias`, `GeminiTeachingClient`, `decodeGeminiResponse`, `postJson`, `readBoundedBody`, `VoiceTransport`, `GeminiLiveTranslator.Inbound`, `GeminiLiveSocket.LIVE_ENDPOINT`, `PcmAudioIO`, `AndroidPcmAudioIO.CAPTURE_CHUNK_BYTES`, `pcm16Level`, `GeminiLiveTransport`, `MuralViewModel.aiProvider/selectAIProvider`, string `settings_provider_data_controls` are used with the same spelling in every task.

## Unresolved questions

1. Google AI Studio usage URL (`https://aistudio.google.com/usage`) is unverified; Task 10 step 2 has the fallback.
2. Whether prefixed user turns steer the model well enough for greeting, theme and help instructions, or whether the greeting should be folded into the initial system instruction. Decide from the first device conversation.
3. Voice `Kore` for all eight languages; revisit per language after listening.
