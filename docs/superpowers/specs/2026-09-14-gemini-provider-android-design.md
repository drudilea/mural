# Gemini as a second AI provider on Android

Date: 2026-09-14. Scope: `apps/android/` only. iOS keeps OpenAI; the design keeps the port mechanical.

## Goals

- Let the Android user choose OpenAI or Gemini in Settings, each with its own API key.
- Voice conversations with Gemini use the Gemini Live API in real time, matching the current OpenAI experience: interruptions, low latency, orb levels, transcripts.
- Text helpers (assessment, typed reply, word lookup, meanings, current topics) work unchanged through the existing `TeachingClient` interface.
- The learning core, teaching prompts, storage, backups and the cross-platform parity script are untouched.

## Non-goals

- iOS changes. Managed accounts, hosted minutes and purchases: unchanged and OpenAI-only.
- Google Search inside the Live session. Ephemeral tokens. A provider-neutral typed event model.

## Verified Gemini API facts (ai.google.dev, 2026-09-14)

| Item | Value |
| --- | --- |
| Live endpoint | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API key>` |
| Live model | `gemini-3.1-flash-live-preview` (preview; free tier) |
| Audio in / out | PCM 16-bit little-endian mono, 16 kHz in, 24 kHz out; `mimeType` `audio/pcm;rate=16000` |
| Setup message | `setup.model` (`models/<id>`), `setup.generationConfig.responseModalities: ["AUDIO"]`, `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName`, `systemInstruction`, `tools`, `inputAudioTranscription: {}`, `outputAudioTranscription: {}` |
| Client messages | `realtimeInput.audio.{data,mimeType}`, `clientContent.{turns,turnComplete}`, `toolResponse.functionResponses[{id,name,response}]` |
| Server messages | `setupComplete`, `serverContent.{modelTurn,interrupted,turnComplete,inputTranscription.text,outputTranscription.text}`, `toolCall.functionCalls[{id,name,args}]`, `toolCallCancellation.ids`, `goAway.timeLeft`, `usageMetadata` |
| Live limits | 15 minutes audio-only per session; `goAway` precedes the cutoff. Explicit language codes are not supported; the model picks the language from the system instruction and speech. |
| Text endpoint | `POST https://generativelanguage.googleapis.com/v1beta/models/<id>:generateContent`, header `x-goog-api-key` |
| Text model | `gemini-3.8-flash` (stable; free tier) |
| Structured output | `generationConfig.responseMimeType: "application/json"` + `generationConfig.responseJsonSchema`; `enum`, `minimum`, `maximum`, `maxItems`, `required`, `additionalProperties` supported |
| Grounding | `tools: [{"googleSearch": {}}]`; sources in `candidates[0].groundingMetadata.groundingChunks[].web.{uri,title}`; 5,000 free searches/month on Gemini 3.x |
| Usage | `usageMetadata.promptTokenCount`, `candidatesTokenCount` |

Structured output and grounding are never requested in the same call; the app already keeps them separate. The Interactions API (GA June 2026) is an alternative to `generateContent`; not used here because its field names were not fully verified.

## Architecture

### Provider selection and credentials

- `enum class AIProvider(val displayName: String, val keyURL: String, val usageURL: String) { OPENAI, GEMINI }` in `core/`.
- `Preferences` gains `aiProvider: AIProvider = OPENAI`, persisted with the existing preferences and included in backups like other settings. Unknown values on import fall back to `OPENAI`.
- `CredentialStore` becomes provider-aware: `save(provider, key)`, `read(provider)`, `delete(provider)`. The existing alias keeps serving OpenAI so saved keys survive without migration; Gemini uses a suffixed alias.
- `MuralViewModel` holds `teaching: TeachingClient` and `transport: VoiceTransport` built by a small factory from the current provider. Changing the provider ends any running conversation (`end("Provider changed")`), then rebuilds both. `HostedAPIClient` paths are reachable only when `aiProvider == OPENAI`.

### Text: `GeminiTeachingClient : TeachingClient`

File `network/GeminiTeachingClient.kt`. Same constructor shape as `APIClient` (credential store, OkHttp client, base URL injectable for tests).

Request: `systemInstruction.parts[0].text = instructions`; `contents = [{role: "user", parts: [{text: input}]}]`; `generationConfig.maxOutputTokens` 1400 / 2200 as today; `thinkingConfig.thinkingLevel: "low"` if accepted by the model, otherwise omitted. With `schema`: `responseMimeType` + `responseJsonSchema`. With `search`: `tools: [{googleSearch: {}}]`.

Response: concatenate `candidates[0].content.parts[].text`. `finishReason` `SAFETY`, `PROHIBITED_CONTENT` or a `promptFeedback.blockReason` → `APIException.Refused`. Empty text or missing candidate → `Incomplete`. Sources from `groundingChunks[].web` deduplicated by URI, `title` fallback "Source"; `usage.searches = 1` when grounding metadata is present. Non-2xx → `APIException.Http(status)`. Missing key → `MissingKey`.

`APIClient.APIException` is reused as-is so `MuralViewModel.resolveMessage` needs no new branches.

### Voice: `VoiceTransport` and `GeminiLiveTransport`

```kotlin
interface VoiceTransport {
    var onEvent: ((JsonObject) -> Unit)?
    var onFailure: ((String) -> Unit)?
    var onLevels: ((Double, Double) -> Unit)?
    val started: Boolean
    val isMuted: Boolean
    suspend fun connect(api: LiveSessionProvider, instructions: String, history: JsonArray, language: String?)
    fun send(event: JsonObject): Boolean
    fun mute(muted: Boolean)
    fun close()
    fun disconnect()
}
```

`LiveTransport` (WebRTC) implements it unchanged. `GeminiLiveTransport` ignores `api` (no SDP exchange) and reads its key from `CredentialStore`. `history` is folded into the first `clientContent` as prior turns.

**Connection.** OkHttp `WebSocket`. Send `setup`; on `setupComplete` emit `session.started` and start audio. Timeouts as today (`READY_TIMEOUT_MILLISECONDS`). Function declaration `request_context(reason: string)` registered in `tools`; description: call when the learner asks about current events, facts you are unsure of, or cultural details worth verifying; the app returns a short sourced brief.

**Audio.** `AudioRecord` with `VOICE_COMMUNICATION`, 16 kHz mono PCM16, 40 ms buffers, base64 into `realtimeInput.audio`. `AudioTrack` streaming 24 kHz mono PCM16; `modelTurn.parts[].inlineData.data` decoded and queued; `interrupted: true` flushes the queue. `CommunicationAudioRoute` handles speaker/headset as today. Levels: RMS of each captured/played buffer, normalized and smoothed with the same 0.35/0.65 blend, muted input reports 0. `mute(true)` stops sending buffers; capture continues so unmute is instant.

**Clock.** Gemini provides neither timestamps nor voice seconds. The transport keeps `connectedAt`; transcript fragments get `start_ms = previous end`, `end_ms = now`. It emits `session.usage.updated` with `{seconds}` every 5 s and on close.

**Inbound mapping.**

| Gemini | Emitted event |
| --- | --- |
| `setupComplete` | `{"type":"session.started"}` |
| `serverContent.inputTranscription.text` | `session.input_transcript.delta` with `delta`, `start_ms`, `end_ms`, `event_id` |
| `serverContent.outputTranscription.text` | `session.output_transcript.delta`, same fields |
| `toolCall.functionCalls[i]` where `name == "request_context"` | `session.delegation.created` with `delegation: {id, target: "client"}` |
| `toolCallCancellation.ids` | cancel pending delegation (drop the pending function response) |
| `goAway` | begin `close()`; `session.closed` follows when the socket closes |
| socket closed after `close()` | `session.closed` with `usage.seconds` |
| socket failure / closed unexpectedly | `onFailure(...)` with the existing transport error strings |

**Outbound mapping** (`send` receives `session.<kind>.append` with `content`, optional `delegation_id`).

| kind | Gemini message |
| --- | --- |
| `instructions` | `clientContent.turns = [{role:"user", parts:[{text: "Instruction from the app, not the learner: <content>"}]}]`, `turnComplete: true` |
| `thinking` | same shape with prefix "Context, not spoken text: ", `turnComplete: false` |
| `commentary` with `delegation_id` | `toolResponse.functionResponses = [{id, name: "request_context", response: {brief: content}}]` |
| `commentary` without id | `clientContent` user turn "Say this to the learner now: <content>", `turnComplete: true` |
| `session.input_audio.mute/unmute`, `session.close` | handled locally (not forwarded) |

`send` returns false when the socket is not open, so the existing "update rejected" notice keeps working.

### Settings and strings

- Provider selector (segmented control, two options) at the top of the account section. Key field, "Get an API key" and "Usage" links follow the provider (`AIProvider.keyURL` / `usageURL`; Gemini → Google AI Studio).
- Strings that name OpenAI in errors, consent and the key section take `%1$s` for `displayName`. OpenAI-only strings (voice price footer, data-controls link, managed account) render only when OpenAI is selected. Both `values/` and `values-es/` updated.
- With Gemini, Settings shows recorded voice time without a cost estimate.

### Errors

Gemini text: mapped to `APIException` as above. Gemini voice: setup rejected (HTTP 4xx during the WebSocket upgrade) → `TransportException` with the existing connection message and `needsKeySetup` when 401/403; 15-minute cutoff → normal `session.closed`, session ends as "Ended by provider".

## Testing

- `GeminiTeachingClientTest`: plain, schema, search with sources, refusal, 401, 429, empty candidate — MockWebServer, mirrors `APIClientTest`.
- `GeminiLiveMessageTranslatorTest`: pure functions in/out, fed recorded JSON; covers clock, delegation id round-trip, cancellation, interrupted flush signal, goAway.
- `GeminiLiveTransportTest`: MockWebServer WebSocket handshake → `session.started`; unexpected close → `onFailure`. No audio devices in unit tests; the audio loop is behind a small interface with a fake.
- `CredentialStoreTest`: per-provider save/read/delete; legacy alias still read for OpenAI.
- `MuralViewModelTest`: provider change rebuilds clients and ends an active conversation; hosted paths unavailable under Gemini.
- Manual on device (Android 11 phone): full Spanish voice conversation, mid-sentence interruption, current-topic request with sources, switch back to OpenAI and confirm its key is still present.

`scripts/check_cross_platform.py` and existing tests must keep passing; prompts are not modified.

## iOS port (out of scope, for reference)

Swift `protocol VoiceTransport` with the same five methods and three callbacks; `URLSessionWebSocketTask` + `AVAudioEngine` implementation; `GeminiAPIClient.respond` with the same body. Reuse the two mapping tables verbatim.

## Open questions

1. Voice name for Gemini (default `Kore`; a per-language choice can follow once heard on device).
2. Whether `thinkingConfig` is accepted by `gemini-3.8-flash` via `generateContent`; if rejected, omit it.
3. Whether prefixed text turns are enough to steer instructions mid-session, or whether the greeting should instead be part of the initial system instruction. Decided at first device test.
