package com.assistant.app.ui

import androidx.lifecycle.ViewModelProvider
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.ui.chat.ChatViewModel
import com.assistant.app.voice.VoiceOption
import com.assistant.app.voice.VoiceOutput
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * UI-test wiring: a chat ViewModel factory over a scripted fake provider, so
 * Robolectric compose tests drive the real screen against deterministic
 * streams. (The app's production wiring lives in [AppContainer].)
 *
 * [initialChatLlm] overrides the resolved generation wiring — e.g.
 * [ChatLlmState.NeedsSetup] for the not-configured state.
 */
class ScriptedChatFixture(
    script: List<ScriptedEvent>,
    initialChatLlm: ChatLlmState? = null,
    /** Makes the top-bar speaker toggle render, as on a device with TTS. */
    ttsAvailable: Boolean = false,
    /** Initial state of the persisted voice-output preference. */
    voiceOutputEnabled: Boolean = false,
    /** Follow-up chips to surface under the last answer. */
    followUps: List<String> = emptyList(),
) {
    val provider: FakeLlmProvider = FakeLlmProvider(script)

    val chatLlm: MutableStateFlow<ChatLlmState> =
        MutableStateFlow(initialChatLlm ?: ChatLlmState.Ready(provider, "test-model"))

    private val voiceOutputEnabledFlow = MutableStateFlow(voiceOutputEnabled)

    val factory: ViewModelProvider.Factory =
        ChatViewModel.Factory(
            repository = ChatRepository(
                chatLlm,
                followUpSuggestions = { _, _, _, _ -> followUps },
            ),
            chatLlm = chatLlm,
            voiceOutput = VoiceOutput(
                if (ttsAvailable) ScriptedVoiceOutput() else UnavailableVoiceOutput(),
            ),
            isVoiceOutputEnabled = { voiceOutputEnabledFlow.value },
            voiceOutputEnabled = voiceOutputEnabledFlow,
            setVoiceOutput = { voiceOutputEnabledFlow.value = it },
        )
}

/** A TTS engine that reports available and completes speech immediately. */
private class ScriptedVoiceOutput : VoiceOutput.Engine {
    override fun isAvailable(): Boolean = true
    override fun voices(onReady: (List<VoiceOption>) -> Unit) = onReady(emptyList())
    override fun speak(text: String, speechRate: Float, voiceId: String?, onDone: () -> Unit) = onDone()
    override fun stop() = Unit
}

/** Stands in for a device with no TTS engine. */
private class UnavailableVoiceOutput : VoiceOutput.Engine {
    override fun isAvailable(): Boolean = false
    override fun voices(onReady: (List<VoiceOption>) -> Unit) = onReady(emptyList())
    override fun speak(text: String, speechRate: Float, voiceId: String?, onDone: () -> Unit) = onDone()
    override fun stop() = Unit
}
