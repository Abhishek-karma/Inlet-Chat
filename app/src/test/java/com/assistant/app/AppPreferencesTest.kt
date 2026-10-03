package com.assistant.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.AppTheme
import com.assistant.app.data.settings.TextSize
import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ReasoningEffort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior contract for application preferences (DataStore): appearance and
 * voice-output round-trips, and the legacy provider fields that exist only
 * until [com.assistant.app.data.ProviderStore.ensureSeeded] migrates them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPreferencesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = AppPreferences(context, kotlinx.coroutines.Dispatchers.Unconfined)

    @Test
    fun `appearance round-trips and defaults to system`() = runTest {
        assertEquals(AppTheme.SYSTEM, preferences.appearance.firstBounded())

        preferences.setAppearance(AppTheme.DARK)
        assertEquals(AppTheme.DARK, preferences.appearance.firstBounded())

        preferences.setAppearance(AppTheme.LIGHT)
        assertEquals(AppTheme.LIGHT, preferences.appearance.firstBounded())

        preferences.setAppearance(AppTheme.SYSTEM)
        assertEquals(AppTheme.SYSTEM, preferences.appearance.firstBounded())
    }

    @Test
    fun `voice output round-trips and defaults to off`() = runTest {
        assertEquals(false, preferences.voiceOutputEnabled.firstBounded())

        preferences.setVoiceOutputEnabled(true)
        assertEquals(true, preferences.voiceOutputEnabled.firstBounded())
    }

    @Test
    fun `text size round-trips and defaults to normal`() = runTest {
        assertEquals(TextSize.NORMAL, preferences.textSize.firstBounded("default text size"))

        preferences.setTextSize(TextSize.LARGE)
        assertEquals(TextSize.LARGE, preferences.textSize.firstBounded("large text size"))

        preferences.setTextSize(TextSize.SMALL)
        assertEquals(TextSize.SMALL, preferences.textSize.firstBounded("small text size"))
    }

    @Test
    fun `reasoning visibility round-trips and defaults to on`() = runTest {
        assertEquals(true, preferences.reasoningVisible.firstBounded())

        preferences.setReasoningVisible(false)
        assertEquals(false, preferences.reasoningVisible.firstBounded())

        preferences.setReasoningVisible(true)
        assertEquals(true, preferences.reasoningVisible.firstBounded())
    }

    @Test
    fun `think selection round-trips per model and defaults to null`() = runTest {
        assertNull(preferences.thinkSelection("gemini-2.5-flash"))

        preferences.setThinkSelection("gemini-2.5-flash", ReasoningConfig.Budget(4096))
        preferences.setThinkSelection("o3-mini", ReasoningConfig.Effort(ReasoningEffort.HIGH))
        assertEquals(ReasoningConfig.Budget(4096), preferences.thinkSelection("gemini-2.5-flash"))
        assertEquals(ReasoningConfig.Effort(ReasoningEffort.HIGH), preferences.thinkSelection("o3-mini"))

        // Keys are normalized, so a differently-cased model id reads the same entry.
        assertEquals(ReasoningConfig.Budget(4096), preferences.thinkSelection("  Gemini-2.5-Flash "))

        // Models without a stored selection stay null.
        assertNull(preferences.thinkSelection("llama3.1:8b"))
    }

    @Test
    fun `voice id round-trips, defaults to null and clears on blank`() = runTest {
        assertNull(preferences.voiceId.firstBounded())

        preferences.setVoiceId("com.google.android.tts:en-us-natural:en-US")
        assertEquals("com.google.android.tts:en-us-natural:en-US", preferences.voiceId.firstBounded())

        // Clearing restores the engine default rather than storing an empty id.
        preferences.setVoiceId(null)
        assertNull(preferences.voiceId.firstBounded())

        preferences.setVoiceId("x")
        preferences.setVoiceId("  ")
        assertNull(preferences.voiceId.firstBounded())
    }

    @Test
    fun `voice speed round-trips and is clamped`() = runTest {
        assertEquals(1.0f, preferences.voiceSpeed.firstBounded())

        preferences.setVoiceSpeed(1.5f)
        assertEquals(1.5f, preferences.voiceSpeed.firstBounded())

        preferences.setVoiceSpeed(5.0f)
        assertEquals(2.0f, preferences.voiceSpeed.firstBounded())

        preferences.setVoiceSpeed(0.1f)
        assertEquals(0.5f, preferences.voiceSpeed.firstBounded())

        preferences.setVoiceSpeed(1.0f)
        assertEquals(1.0f, preferences.voiceSpeed.firstBounded())
    }

    @Test
    fun `voice auto-play round-trips and defaults to on`() = runTest {
        assertEquals(true, preferences.voiceAutoPlay.firstBounded())

        preferences.setVoiceAutoPlay(false)
        assertEquals(false, preferences.voiceAutoPlay.firstBounded())

        preferences.setVoiceAutoPlay(true)
        assertEquals(true, preferences.voiceAutoPlay.firstBounded())
    }

    @Test
    fun `legacy provider config round-trips through install and clear`() = runTest {
        assertNull(preferences.legacyProviderConfig())

        preferences.installLegacyProviderConfig("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini")
        val legacy = preferences.legacyProviderConfig()
        assertEquals("OpenAI", legacy?.name)
        assertEquals("https://api.openai.com/v1", legacy?.baseUrl)
        assertEquals("gpt-4o-mini", legacy?.model)

        preferences.clearLegacyProviderConfig()
        assertNull(preferences.legacyProviderConfig())
    }
}
