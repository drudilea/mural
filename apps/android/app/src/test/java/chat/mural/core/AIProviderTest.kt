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
