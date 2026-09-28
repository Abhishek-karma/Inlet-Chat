package com.assistant.app.ui.chat

import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatUiState
import com.assistant.app.data.VoiceStatus
import com.assistant.app.llm.model.Role
import com.assistant.app.voice.VoiceInput
import com.assistant.app.voice.VoiceInputError
import com.assistant.app.voice.VoiceInputEvent
import com.assistant.app.voice.VoiceOutput
import com.assistant.app.voice.speakableText

/**
 * Handles voice input (microphone transcription) and voice output (TTS playback).
 */
class VoiceHandler(
    private val repository: ChatRepository,
    private val voiceInput: VoiceInput = VoiceInput.unavailable(),
    private val voiceOutput: VoiceOutput = VoiceOutput.unavailable(),
    private val isVoiceOutputEnabled: () -> Boolean = { false },
    private val voiceAutoPlay: () -> Boolean = { true },
    private val voiceSpeed: () -> Float = { 1.0f },
    private val voiceId: () -> String? = { null },
) {
    private var recognitionSession = 0
    private var speechSession = 0
    var suppressNextSpeak = false

    val isVoiceInputAvailable: Boolean get() = voiceInput.isAvailable
    val ttsAvailable: Boolean get() = voiceOutput.isAvailable
    val speakAvailable: Boolean get() = isVoiceOutputEnabled() && voiceOutput.isAvailable

    fun onMicClick() {
        if (!voiceInput.isAvailable) return
        if (repository.uiState.value.voiceStatus == VoiceStatus.Speaking) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        val currentStatus = repository.uiState.value.voiceStatus
        if (currentStatus == VoiceStatus.Listening || currentStatus == VoiceStatus.Transcribing) {
            stopListening()
            return
        }
        repository.setVoiceHint(false)
        recognitionSession++
        repository.setVoiceStatus(VoiceStatus.Listening)
        val session = recognitionSession
        voiceInput.start { event ->
            if (session != recognitionSession) return@start
            onVoiceEvent(event)
        }
    }

    private fun onVoiceEvent(event: VoiceInputEvent) {
        when (event) {
            is VoiceInputEvent.Transcribing -> repository.setVoiceStatus(VoiceStatus.Transcribing)
            is VoiceInputEvent.Transcript -> {
                recognitionSession++
                repository.setVoiceStatus(VoiceStatus.Idle)
                repository.applyVoiceTranscript(event.text)
            }
            is VoiceInputEvent.Failed -> {
                recognitionSession++
                repository.setVoiceStatus(VoiceStatus.Idle)
                if (event.kind == VoiceInputError.NoMatch) {
                    repository.setVoiceHint(true)
                }
            }
        }
    }

    fun stopListening() {
        recognitionSession++
        voiceInput.stop()
        val status = repository.uiState.value.voiceStatus
        if (status == VoiceStatus.Listening || status == VoiceStatus.Transcribing) {
            repository.setVoiceStatus(VoiceStatus.Idle)
        }
    }

    fun stopSpeaking() {
        if (repository.uiState.value.voiceStatus == VoiceStatus.Speaking) {
            speechSession++
            voiceOutput.stop()
            repository.setVoiceStatus(VoiceStatus.Idle)
        }
    }

    fun speakCompletedAssistantMessage(state: ChatUiState) {
        if (!isVoiceOutputEnabled() || !voiceAutoPlay() || suppressNextSpeak) return
        val text = state.messages.lastOrNull()
            ?.takeIf { it.role == Role.ASSISTANT && it.content.isNotBlank() }
            ?.content
            ?: return
        startSpeaking(text)
    }

    fun speakMessage(messageId: String, state: ChatUiState) {
        val message = state.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != Role.ASSISTANT || message.content.isBlank()) return
        startSpeaking(message.content)
    }

    private fun startSpeaking(content: String) {
        if (!voiceOutput.isAvailable) return
        speechSession++
        val session = speechSession
        repository.setVoiceStatus(VoiceStatus.Speaking)
        voiceOutput.speak(speakableText(content), voiceSpeed(), voiceId()) {
            if (session == speechSession) {
                repository.setVoiceStatus(VoiceStatus.Idle)
            }
        }
    }
}
