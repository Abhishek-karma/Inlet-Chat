package com.assistant.app.ui.settings

/** One library section of the generated licenses asset. */
internal data class LibraryLicense(val name: String, val license: String)

internal object LibraryLicenses {

    /**
     * Parses the `licenses.txt` the build generates from the app's Gradle
     * dependencies. The first section is the leading text before the first
     * heading, which the generator never writes, so it is skipped.
     */
    fun parse(text: String): List<LibraryLicense> {
        val sections = text.split("\n## ").drop(1)
        return sections.mapNotNull { section ->
            val heading = section.substringBefore("\n").removePrefix("## ").trim()
            val license = section.substringAfter("\n", "").trim()
            if (heading.isEmpty()) null else LibraryLicense(heading, license)
        }
    }
}

/**
 * Starters for the OpenAI-compatible endpoints this app already ships as
 * presets. Each entry only fills the form; the API key always comes from the
 * user. Key URLs are the providers' own console pages.
 */
internal data class ProviderPreset(
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
)

internal val ProviderPresets = listOf(
    ProviderPreset(
        name = "Gemini",
        baseUrl = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-2.5-flash",
        keyUrl = "https://aistudio.google.com/app/apikey",
    ),
    ProviderPreset(
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        keyUrl = "https://platform.openai.com/api-keys",
    ),
    ProviderPreset(
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        keyUrl = "https://openrouter.ai/keys",
    ),
    ProviderPreset(
        name = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        keyUrl = "https://console.groq.com/keys",
    ),
    ProviderPreset(
        name = "Naga",
        baseUrl = "https://api.naga.ac/v1",
        defaultModel = "dots-3-note-preview:free",
        keyUrl = "https://naga.ac",
    ),
)

internal const val GEMINI_NAME = "Gemini"
internal const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
internal const val GEMINI_DEFAULT_MODEL = "gemini-2.5-flash"
internal const val GEMINI_KEY_URL = "https://aistudio.google.com/app/apikey"

internal fun isGemini(baseUrl: String, name: String = ""): Boolean {
    val b = baseUrl.trim().lowercase()
    val n = name.trim().lowercase()
    return b.contains("generativelanguage.googleapis.com") ||
        n.contains("gemini") ||
        b.contains("gemini")
}

internal fun presetForBaseUrl(baseUrl: String): ProviderPreset? {
    val normalized = baseUrl.trim().trimEnd('/')
    return ProviderPresets.firstOrNull { it.baseUrl.trimEnd('/') == normalized }
}
