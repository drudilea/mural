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
    /** HTTP statuses that mean the personal key is missing, malformed or rejected. */
    val keyErrorStatuses: Set<Int>,
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
        keyErrorStatuses = setOf(401),
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
        keyErrorStatuses = setOf(400, 401, 403),
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
