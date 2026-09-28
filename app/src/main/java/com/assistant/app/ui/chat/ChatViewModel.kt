package com.assistant.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.assistant.app.data.AttachmentIngester
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.data.ChatUiState
import com.assistant.app.data.VoiceStatus
import com.assistant.app.data.local.ConversationEntity
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.llm.model.Role
import com.assistant.app.voice.VoiceInput
import com.assistant.app.voice.VoiceInputError
import com.assistant.app.voice.VoiceInputEvent
import com.assistant.app.voice.VoiceOutput
import com.assistant.app.voice.speakableText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Bridges the chat UI and ChatRepository: exposes repository state as
 * [uiState] and forwards user intents. Voice layers on the text pipeline.
 */
class ChatViewModel(
    private val repository: ChatRepository,
    chatLlm: StateFlow<ChatLlmState>,
    private val voiceInput: VoiceInput = VoiceInput.unavailable(),
    private val voiceOutput: VoiceOutput = VoiceOutput.unavailable(),
    private val isVoiceOutputEnabled: () -> Boolean = { false },
    val providers: Flow<List<ProviderEntity>> = emptyFlow(),
    private val activateProviderById: suspend (Long) -> Unit = {},
    private val attachmentIngester: AttachmentIngester? = null,
    val reasoningVisible: StateFlow<Boolean> = MutableStateFlow(true),
    private val voiceAutoPlay: () -> Boolean = { true },
    private val voiceSpeed: () -> Float = { 1.0f },
    private val voiceId: () -> String? = { null },
    /** The search control hides when no service is configured. */
    private val searchConfigured: StateFlow<Boolean> = MutableStateFlow(false),
    val voiceOutputEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    private val setVoiceOutput: suspend (Boolean) -> Unit = {},
) : ViewModel() {

    /** Bumped per recognition so a late result from a stopped one is ignored. */
    private var recognitionSession = 0

    val uiState: StateFlow<ChatUiState> = repository.uiState

    val chatLlm: StateFlow<ChatLlmState> = chatLlm

    val conversations: Flow<List<ConversationEntity>> = repository.conversations

    val isVoiceInputAvailable: Boolean get() = voiceInput.isAvailable

    val attachmentSupport: Boolean get() = attachmentIngester != null

    val searchAvailable: StateFlow<Boolean> = searchConfigured

    /**
     * Whether the device can speak. Hiding the speaker once muted would leave
     * no way back, so the top bar keys off this rather than [speakAvailable].
     */
    val ttsAvailable: Boolean get() = voiceOutput.isAvailable

    val speakAvailable: Boolean get() = isVoiceOutputEnabled() && voiceOutput.isAvailable

    /** Guard against stale TTS completions after a new utterance started. */
    private var speechSession = 0

    /**
     * Set when the user cancels a generation; the partial response then must
     * not be spoken. Cleared on the next explicit send intent.
     */
    private var suppressNextSpeak = false

    init {
        viewModelScope.launch {
            chatLlm.collect { state ->
                when (state) {
                    is ChatLlmState.Ready -> repository.setNeedsSetup(false)
                    is ChatLlmState.NeedsSetup -> repository.setNeedsSetup(true)
                    is ChatLlmState.Loading -> Unit
                }
            }
        }
        viewModelScope.launch {
            var previousStatus: ChatStatus = ChatStatus.Idle
            repository.uiState.collect { state ->
                val wasGenerating = previousStatus is ChatStatus.Generating
                previousStatus = state.status
                if (wasGenerating && state.status is ChatStatus.Idle && !suppressNextSpeak) {
                    speakCompletedAssistantMessage(state)
                }
            }
        }
    }

    class Factory(
        private val repository: ChatRepository,
        private val chatLlm: StateFlow<ChatLlmState>,
        private val voiceInput: VoiceInput = VoiceInput.unavailable(),
        private val voiceOutput: VoiceOutput = VoiceOutput.unavailable(),
        private val isVoiceOutputEnabled: () -> Boolean = { false },
        private val providers: Flow<List<ProviderEntity>> = emptyFlow(),
        private val activateProviderById: suspend (Long) -> Unit = {},
        private val attachmentIngester: AttachmentIngester? = null,
        private val reasoningVisible: StateFlow<Boolean> = MutableStateFlow(true),
        private val voiceAutoPlay: () -> Boolean = { true },
        private val voiceSpeed: () -> Float = { 1.0f },
        private val voiceId: () -> String? = { null },
        private val searchAvailable: StateFlow<Boolean> = MutableStateFlow(false),
        private val voiceOutputEnabled: StateFlow<Boolean> = MutableStateFlow(false),
        private val setVoiceOutput: suspend (Boolean) -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return ChatViewModel(
                repository,
                chatLlm,
                voiceInput,
                voiceOutput,
                isVoiceOutputEnabled,
                providers,
                activateProviderById,
                attachmentIngester,
                reasoningVisible,
                voiceAutoPlay,
                voiceSpeed,
                voiceId,
                searchAvailable,
                voiceOutputEnabled,
                setVoiceOutput,
            ) as T
        }
    }

/** Ends all voice activity when leaving the chat screen. */
    override fun onCleared() {
        stopListening()
        stopSpeaking()
    }

    fun send(text: String) {
        stopSpeaking()
        suppressNextSpeak = false
        viewModelScope.launch { repository.send(text) }
    }

    fun stop() {
        suppressNextSpeak = true
        repository.stop()
    }

    fun retry() {
        stopSpeaking()
        suppressNextSpeak = false
        viewModelScope.launch { repository.retry() }
    }

    fun dismissError() {
        repository.dismissError()
    }

    fun dismissVoiceHint() {
        repository.setVoiceHint(false)
    }

    fun regenerate() {
        stopSpeaking()
        suppressNextSpeak = false
        viewModelScope.launch { repository.regenerate() }
    }

    fun switchVersion(messageId: String, index: Int) {
        viewModelScope.launch { repository.switchVersion(messageId, index) }
    }

    fun setConversationPinned(id: String, pinned: Boolean) {
        viewModelScope.launch { repository.setConversationPinned(id, pinned) }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    fun activateProvider(id: Long) {
        viewModelScope.launch { activateProviderById(id) }
    }

    fun addImageAttachments(uris: List<Uri>) {
        val ingester = attachmentIngester ?: return
        viewModelScope.launch {
            val results = uris.map { ingester.ingestImage(it) }
            applyIngestResults(results)
        }
    }

    fun addTextAttachment(uri: Uri) {
        val ingester = attachmentIngester ?: return
        viewModelScope.launch {
            applyIngestResults(listOf(ingester.ingestText(uri)))
        }
    }

    fun removePendingAttachment(id: String) {
        repository.removePendingAttachment(id)
    }

    fun dismissAttachmentError() {
        repository.clearAttachmentError()
    }

    fun toggleSearch() {
        viewModelScope.launch {
            repository.setSearchEnabled(!repository.uiState.value.searchEnabled)
        }
    }

    fun dismissSearchNotice() {
        repository.dismissSearchNotice()
    }

    suspend fun shareConversationText(id: String): String? = repository.shareConversationText(id)

    private fun applyIngestResults(results: List<AttachmentIngester.IngestResult>) {
        repository.addPendingAttachments(
            results.mapNotNull { (it as? AttachmentIngester.IngestResult.Success)?.attachment },
        )
        results.firstOrNull { it is AttachmentIngester.IngestResult.Failure }
            ?.let { repository.setAttachmentError((it as AttachmentIngester.IngestResult.Failure).message) }
    }

    fun editAndResend(messageId: String, newContent: String) {
        stopSpeaking()
        suppressNextSpeak = false
        viewModelScope.launch { repository.editAndResend(messageId, newContent) }
    }

    fun setDraft(text: String) {
        repository.setDraft(text)
    }

    fun openConversation(id: String) {
        stopListening()
        stopSpeaking()
        // Switching conversations must never speak: the repository settles the
        // interrupted generation (or the opened one) into Idle, which the
        // watcher would otherwise read as a freshly completed answer.
        suppressNextSpeak = true
        viewModelScope.launch { repository.openConversation(id) }
    }

    fun newConversation() {
        stopListening()
        stopSpeaking()
        suppressNextSpeak = true
        repository.newConversation()
    }

    fun deleteConversation(id: String) {
        stopSpeaking()
        // Deleting the OPEN conversation stops its generation and settles the
        // status into Idle — which the watcher must not read as a fresh
        // answer. Deleting another conversation leaves the open generation
        // (and its eventual spoken answer) untouched.
        if (repository.uiState.value.conversationId == id) {
            suppressNextSpeak = true
        }
        viewModelScope.launch { repository.deleteConversation(id) }
    }

    fun onMicClick() {
        if (!voiceInput.isAvailable) return
        // While an answer is being spoken, the mic tap stops the speech.
        if (repository.uiState.value.voiceStatus == VoiceStatus.Speaking) {
            stopSpeaking()
            return
        }
        stopSpeaking()
        if (uiState.value.voiceStatus == VoiceStatus.Listening ||
            uiState.value.voiceStatus == VoiceStatus.Transcribing
        ) {
            stopListening()
            return
        }
        repository.setVoiceHint(false)
        recognitionSession++
        repository.setVoiceStatus(VoiceStatus.Listening)
        val session = recognitionSession
        voiceInput.start { event ->
            // Ignore events from an attempt that was replaced or cancelled.
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
                // Other kinds (mic unavailable, busy, permission): the chat is
                // unaffected; the screen falls back to typing silently.
            }
        }
    }

    private fun stopListening() {
        recognitionSession++
        voiceInput.stop()
        val status = repository.uiState.value.voiceStatus
        if (status == VoiceStatus.Listening || status == VoiceStatus.Transcribing) {
            repository.setVoiceStatus(VoiceStatus.Idle)
        }
    }

    private fun stopSpeaking() {
        if (repository.uiState.value.voiceStatus == VoiceStatus.Speaking) {
            speechSession++
            voiceOutput.stop()
            repository.setVoiceStatus(VoiceStatus.Idle)
        }
    }

    private fun speakCompletedAssistantMessage(state: ChatUiState) {
        if (!isVoiceOutputEnabled() || !voiceAutoPlay()) return
        val text = state.messages.lastOrNull()
            ?.takeIf { it.role == Role.ASSISTANT && it.content.isNotBlank() }
            ?.content
            ?: return
        startSpeaking(text)
    }

    fun toggleVoiceOutput() {
        val next = !voiceOutputEnabled.value
        viewModelScope.launch {
            setVoiceOutput(next)
        }
    }

    fun speakMessage(messageId: String) {
        val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != Role.ASSISTANT || message.content.isBlank()) return
        startSpeaking(message.content)
    }

    private fun startSpeaking(content: String) {
        if (!voiceOutput.isAvailable) return
        speechSession++
        val session = speechSession
        repository.setVoiceStatus(VoiceStatus.Speaking)
        voiceOutput.speak(speakableText(content), voiceSpeed(), voiceId()) {
            // A newer utterance or an explicit cancel owns the state now.
            if (session == speechSession) {
                repository.setVoiceStatus(VoiceStatus.Idle)
            }
        }
    }
}
