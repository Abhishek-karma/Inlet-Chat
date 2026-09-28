package com.assistant.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app-wide appearance. [SYSTEM] follows the device's
 * light/dark theme; the other two force one.
 */
enum class AppTheme { SYSTEM, LIGHT, DARK }

/**
 * The reading text size; [scale] multiplies the user's system
 * font scale.
 */
enum class TextSize(val scale: Float) { SMALL(0.9f), NORMAL(1.0f), LARGE(1.15f) }

/**
 * Application preferences (DataStore): voice output and appearance. Provider
 * configurations live in [com.assistant.app.data.ProviderStore]; the legacy
 * pre-1.2 provider fields below are read exactly once by its seeding and then
 * deleted.
 */
class AppPreferences(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext

    private val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { File(appContext.filesDir, DATA_STORE_FILE) },
    )

    /** Whether completed assistant messages should be spoken. */
    val voiceOutputEnabled: Flow<Boolean> = dataStore.data.map { prefs -> prefs[KEY_VOICE_OUTPUT] ?: false }

    /** Persists the voice-output preference immediately. */
    suspend fun setVoiceOutputEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_VOICE_OUTPUT] = enabled }
    }

    /** The app-wide appearance; [AppTheme.SYSTEM] until the user picks one. */
    val appearance: Flow<AppTheme> = dataStore.data.map { prefs ->
        prefs[KEY_APPEARANCE]?.let { stored ->
            AppTheme.entries.firstOrNull { it.name == stored }
        } ?: AppTheme.SYSTEM
    }

    /** Persists the appearance preference immediately. */
    suspend fun setAppearance(theme: AppTheme) {
        dataStore.edit { prefs -> prefs[KEY_APPEARANCE] = theme.name }
    }

    /**
 * Whether the chat shows real model reasoning above answers when the
 * provider sends it; on by default.
     */
    val reasoningVisible: Flow<Boolean> = dataStore.data.map { prefs -> prefs[KEY_REASONING_VISIBLE] ?: true }

    /** Persists the reasoning-visibility preference immediately. */
    suspend fun setReasoningVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_REASONING_VISIBLE] = visible }
    }


    /** Whether web search is enabled/available for use. Available out of the box. */
    val searchConfigured: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SEARCH_CONFIGURED] ?: true
    }

    /** Records whether search is configured/enabled. */
    suspend fun setSearchConfigured(configured: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_SEARCH_CONFIGURED] = configured }
    }

    /** Configured SearXNG instance endpoint URL. */
    val searchEndpoint: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SEARCH_ENDPOINT]?.takeIf { it.isNotBlank() } ?: DEFAULT_SEARCH_ENDPOINT
    }

    /** Updates the configured search endpoint. */
    suspend fun setSearchEndpoint(endpoint: String?) {
        dataStore.edit { prefs ->
            if (endpoint.isNullOrBlank()) {
                prefs.remove(KEY_SEARCH_ENDPOINT)
            } else {
                prefs[KEY_SEARCH_ENDPOINT] = endpoint.trim()
            }
        }
    }

    /** The reading text size; [TextSize.NORMAL] until the user picks one. */
    val textSize: Flow<TextSize> = dataStore.data.map { prefs ->
        prefs[KEY_TEXT_SIZE]?.let { stored -> TextSize.entries.firstOrNull { it.name == stored } }
            ?: TextSize.NORMAL
    }

    /** Persists the text-size preference immediately. */
    suspend fun setTextSize(size: TextSize) {
        dataStore.edit { prefs -> prefs[KEY_TEXT_SIZE] = size.name }
    }

    /**
     * Whether a completed assistant message is spoken automatically; on by
     * default. When off, the UI can still speak a message explicitly via the
     * ViewModel.
     */
    val voiceAutoPlay: Flow<Boolean> = dataStore.data.map { prefs -> prefs[KEY_VOICE_AUTO_PLAY] ?: true }

    /** Persists the auto-play preference immediately. */
    suspend fun setVoiceAutoPlay(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_VOICE_AUTO_PLAY] = enabled }
    }

    /** Whether onboarding has been completed; false until the user finishes or skips it. */
    val onboardingDone: Flow<Boolean> = dataStore.data.map { prefs -> prefs[KEY_ONBOARDING_DONE] ?: false }

    /** Persists the onboarding-completed flag immediately. */
    suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ONBOARDING_DONE] = done }
    }

    /** The speech rate for spoken answers, clamped to 0.5..2.0; 1.0 by default. */
    val voiceSpeed: Flow<Float> = dataStore.data.map { prefs ->
        (prefs[KEY_VOICE_SPEED] ?: DEFAULT_VOICE_SPEED).coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)
    }

    /** Persists the speech rate, clamped to 0.5..2.0. */
    suspend fun setVoiceSpeed(speed: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_VOICE_SPEED] = speed.coerceIn(MIN_VOICE_SPEED, MAX_VOICE_SPEED)
        }
    }

    /**
     * The chosen [com.assistant.app.voice.VoiceOption] id, or null for the
     * engine's own default. A stale id is ignored at speak time, so removing a
     * language pack is safe.
     */
    val voiceId: Flow<String?> = dataStore.data.map { prefs -> prefs[KEY_VOICE_ID] }

    suspend fun setVoiceId(voiceId: String?) {
        dataStore.edit { prefs ->
            if (voiceId.isNullOrBlank()) prefs.remove(KEY_VOICE_ID) else prefs[KEY_VOICE_ID] = voiceId
        }
    }

    /**
 * The pre-1.2 single provider configuration, or null once it has been
 * migrated into the provider store (or never existed).
     */
    suspend fun legacyProviderConfig(): LegacyProviderConfig? {
        val prefs = dataStore.data.first()
        val name = prefs[KEY_NAME].orEmpty()
        val baseUrl = prefs[KEY_BASE_URL].orEmpty()
        val model = prefs[KEY_MODEL].orEmpty()
        if (name.isBlank() && baseUrl.isBlank() && model.isBlank()) return null
        return LegacyProviderConfig(
            name = name,
            baseUrl = baseUrl,
            model = model,
            enabled = prefs[KEY_ENABLED] ?: true,
        )
    }

    /** Deletes the legacy provider fields after seeding. */
    suspend fun clearLegacyProviderConfig() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_NAME)
            prefs.remove(KEY_BASE_URL)
            prefs.remove(KEY_MODEL)
            prefs.remove(KEY_ENABLED)
            prefs.remove(KEY_ID)
        }
    }

    /**
 * Recreates the pre-1.2 DataStore state so migration tests can exercise
 * seeding without a real upgrade; never called by the app.
     */
    internal suspend fun installLegacyProviderConfig(name: String, baseUrl: String, model: String) {
        dataStore.edit { prefs ->
            prefs[KEY_NAME] = name
            prefs[KEY_BASE_URL] = baseUrl
            prefs[KEY_MODEL] = model
            prefs[KEY_ENABLED] = true
        }
    }

    /** The non-secret parts of the pre-1.2 provider configuration. */
    data class LegacyProviderConfig(
        val name: String,
        val baseUrl: String,
        val model: String,
        val enabled: Boolean,
    )

    companion object {
        private const val DATA_STORE_FILE = "provider_settings.preferences_pb"

        private val KEY_ID = longPreferencesKey("id")
        private val KEY_NAME = stringPreferencesKey("name")
        private val KEY_BASE_URL = stringPreferencesKey("base_url")
        private val KEY_MODEL = stringPreferencesKey("model")
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_VOICE_OUTPUT = booleanPreferencesKey("voice_output_enabled")
        private val KEY_APPEARANCE = stringPreferencesKey("appearance")
        private val KEY_REASONING_VISIBLE = booleanPreferencesKey("reasoning_visible")
        private val KEY_TEXT_SIZE = stringPreferencesKey("text_size")
        private val KEY_VOICE_AUTO_PLAY = booleanPreferencesKey("voice_auto_play")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_VOICE_SPEED = floatPreferencesKey("voice_speed")
        private val KEY_VOICE_ID = stringPreferencesKey("voice_id")
        private val KEY_SEARCH_CONFIGURED = booleanPreferencesKey("search_configured")
        private val KEY_SEARCH_ENDPOINT = stringPreferencesKey("search_endpoint")

        const val DEFAULT_SEARCH_ENDPOINT = "https://searx.be/search"
        const val DEFAULT_VOICE_SPEED = 1.0f
        const val MIN_VOICE_SPEED = 0.5f
        const val MAX_VOICE_SPEED = 2.0f
    }
}
