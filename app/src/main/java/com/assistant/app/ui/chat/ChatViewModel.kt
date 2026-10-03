package com.assistant.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assistant.app.data.AttachmentIngester
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.data.ChatUiState
import com.assistant.app.data.local.ConversationEntity
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.voice.VoiceInput
import com.assistant.app.ui.history.ConversationSummary
import com.assistant.app.voice.VoiceOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin UI-facing coordinator bridging Jetpack Compose and application services.
 * Delegates voice processing to [VoiceHandler] and chat operations to [ChatRepository].
 */
class ChatViewModel(
    private val repository: ChatRepository,
    chatLlm: StateFlow<ChatLlmState>,
    voiceInput: VoiceInput = VoiceInput.unavailable(),
    voiceOutput: VoiceOutput = VoiceOutput.unavailable(),
    isVoiceOutputEnabled: () -> Boolean = { false },
    val providers: Flow<List<ProviderEntity>> = emptyFlow(),
    private val activateProviderById: suspend (Long) -> Unit = {},
    private val attachmentIngester: AttachmentIngester? = null,
    val reasoningVisible: StateFlow<Boolean> = MutableStateFlow(true),
    voiceAutoPlay: () -> Boolean = { true },
    voiceSpeed: () -> Float = { 1.0f },
    voiceId: () -> String? = { null },
    val voiceOutputEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    private val setVoiceOutput: suspend (Boolean) -> Unit = {},
) : ViewModel() {

    private val voiceHandler = VoiceHandler(
        repository = repository,
        voiceInput = voiceInput,
        voiceOutput = voiceOutput,
        isVoiceOutputEnabled = isVoiceOutputEnabled,
        voiceAutoPlay = voiceAutoPlay,
        voiceSpeed = voiceSpeed,
        voiceId = voiceId,
    )

    val uiState: StateFlow<ChatUiState> = repository.uiState
    val chatLlm: StateFlow<ChatLlmState> = chatLlm
    val conversations: StateFlow<List<ConversationEntity>> = repository.conversations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val conversationSummaries: StateFlow<List<ConversationSummary>> = repository.conversations
        .map { list ->
            list.map { ConversationSummary(it.id, it.title, it.updatedAt, it.pinned) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val savedProviders: StateFlow<List<ProviderEntity>> = providers
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val isVoiceInputAvailable: Boolean get() = voiceHandler.isVoiceInputAvailable
    val attachmentSupport: Boolean get() = attachmentIngester != null
    val ttsAvailable: Boolean get() = voiceHandler.ttsAvailable
    val speakAvailable: Boolean get() = voiceHandler.speakAvailable

    init {
        viewModelScope.launch {
            chatLlm.collect { state ->
                when (state) {
                    is ChatLlmState.Ready -> {
                        repository.setNeedsSetup(false)
                        repository.onThinkModelChanged(state.model, state.thinkCapability)
                    }
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
                if (wasGenerating && state.status is ChatStatus.Idle) {
                    voiceHandler.speakCompletedAssistantMessage(state)
                }
            }
        }
    }

    override fun onCleared() {
        voiceHandler.stopListening()
        voiceHandler.stopSpeaking()
    }

    fun send(text: String) {
        voiceHandler.stopSpeaking()
        voiceHandler.suppressNextSpeak = false
        viewModelScope.launch { repository.send(text) }
    }

    fun stop() {
        voiceHandler.suppressNextSpeak = true
        repository.stop()
    }

    fun retry() {
        voiceHandler.stopSpeaking()
        voiceHandler.suppressNextSpeak = false
        viewModelScope.launch { repository.retry() }
    }

    fun dismissError() {
        repository.dismissError()
    }

    fun dismissVoiceHint() {
        repository.setVoiceHint(false)
    }

    fun regenerate() {
        voiceHandler.stopSpeaking()
        voiceHandler.suppressNextSpeak = false
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
            repository.setIngestingAttachments(true)
            val results = uris.map { ingester.ingestImage(it) }
            applyIngestResults(results)
        }
    }

    fun addTextAttachment(uri: Uri) {
        val ingester = attachmentIngester ?: return
        viewModelScope.launch {
            repository.setIngestingAttachments(true)
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

    fun setThinkConfig(config: ReasoningConfig) {
        viewModelScope.launch { repository.setThinkConfig(config) }
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
        voiceHandler.stopSpeaking()
        voiceHandler.suppressNextSpeak = false
        viewModelScope.launch { repository.editAndResend(messageId, newContent) }
    }

    fun setDraft(text: String) {
        repository.setDraft(text)
    }

    fun openConversation(id: String) {
        voiceHandler.stopListening()
        voiceHandler.stopSpeaking()
        voiceHandler.suppressNextSpeak = true
        viewModelScope.launch { repository.openConversation(id) }
    }

    fun newConversation() {
        voiceHandler.stopListening()
        voiceHandler.stopSpeaking()
        voiceHandler.suppressNextSpeak = true
        repository.newConversation()
    }

    fun deleteConversation(id: String) {
        voiceHandler.stopSpeaking()
        if (repository.uiState.value.conversationId == id) {
            voiceHandler.suppressNextSpeak = true
        }
        viewModelScope.launch { repository.deleteConversation(id) }
    }

    fun onMicClick() {
        voiceHandler.onMicClick()
    }

    fun toggleVoiceOutput() {
        val next = !voiceOutputEnabled.value
        viewModelScope.launch { setVoiceOutput(next) }
    }

    fun speakMessage(messageId: String) {
        voiceHandler.speakMessage(messageId, uiState.value)
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
                voiceOutputEnabled,
                setVoiceOutput,
            ) as T
        }
    }
}
