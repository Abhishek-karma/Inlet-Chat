package com.assistant.app.data

import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ThinkCapability
import com.assistant.app.llm.model.UiAttachment
import com.assistant.app.llm.model.UiMessage

/**
 * Status of the active generation in the chat session.
 */
sealed interface ChatStatus {
    data object Idle : ChatStatus
    data object Generating : ChatStatus
    data class Error(val message: String) : ChatStatus
}

/**
 * Voice state layered cleanly on the text pipeline:
 * Idle -> Listening -> Processing -> Speaking -> Error.
 */
enum class VoiceStatus {
    Idle,
    Listening,
    Processing,
    Speaking,
    Error;

    companion object {
        val Transcribing: VoiceStatus get() = Processing
    }
}

/**
 * Complete UI state observed by the chat screen.
 */
data class ChatUiState(
    val conversationId: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val status: ChatStatus = ChatStatus.Idle,
    val draft: String = "",
    val needsSetup: Boolean = false,
    val voiceStatus: VoiceStatus = VoiceStatus.Idle,
    /** One-shot "didn't catch that" hint above the composer. */
    val voiceHint: Boolean = false,
    /** Files staged in the composer for the next message. */
    val pendingAttachments: List<UiAttachment> = emptyList(),
    /** One-shot attachment ingest/limit error above the composer. */
    val attachmentError: String? = null,
    /** Web search for the current conversation. */
    val searchEnabled: Boolean = false,
    /** One-shot web-search notice above the composer. */
    val searchNotice: String? = null,
    /** Whether attachments are currently being processed or ingested. */
    val isIngestingAttachments: Boolean = false,
    /** Reasoning control the active model exposes; [ThinkCapability.Unsupported] hides the Think control. */
    val thinkCapability: ThinkCapability = ThinkCapability.Unsupported,
    /** The selected reasoning configuration, captured when a generation starts. */
    val thinkConfig: ReasoningConfig = ReasoningConfig.Auto,
)

/**
 * Generation wiring resolved from the active provider.
 */
sealed interface ChatLlmState {
    data object Loading : ChatLlmState
    data class Ready(
        val provider: LlmProvider,
        val model: String,
        val providerId: Long = 0,
        val name: String = "",
        /** What reasoning control the active model exposes. */
        val thinkCapability: ThinkCapability = ThinkCapability.Unsupported,
    ) : ChatLlmState
    data object NeedsSetup : ChatLlmState
}
