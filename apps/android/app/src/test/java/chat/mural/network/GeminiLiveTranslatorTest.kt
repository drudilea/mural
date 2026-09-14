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
