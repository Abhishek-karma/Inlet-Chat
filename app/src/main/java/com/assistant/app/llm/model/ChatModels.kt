package com.assistant.app.llm.model

enum class Role { USER, ASSISTANT, SYSTEM }

/**
 * One conversation message as the UI sees it. For assistant messages,
 * [versions] holds every completed answer for this message slot with
 * [selectedVersion] pointing at the one in [content] — empty for messages
 * that were never regenerated. [followUps] carries any suggested follow-up
 * questions. [attachments] carries the files attached
 * to a user message, if any. [reasoning] holds the real
 * model reasoning streamed with the current answer — empty when the provider
 * sent none, and cleared when the answer is regenerated or replaced.
 */
data class UiMessage(
    val id: String,
    val role: Role,
    val content: String,
    val createdAt: Long,
    val versions: List<String> = emptyList(),
    val selectedVersion: Int = 0,
    val attachments: List<UiAttachment> = emptyList(),
    val reasoning: String = "",
    /** Web sources shown under this answer; persisted. */
    val sources: List<SearchResult> = emptyList(),
    val followUps: List<String> = emptyList(),
    /** Search results injected into the request context; not persisted. */
    val webResults: List<SearchResult> = emptyList(),
)

/** Provider-internal request shape; never exposed to the UI. */
data class ChatRequest(
    val model: String,
    val messages: List<Pair<Role, String>>,
    /**
 * Data-URL images attached to the final user message;
 * empty for text-only requests. The wire format for that message becomes
 * the standard multi-content array, others stay plain strings.
     */
    val images: List<String> = emptyList(),
)

/**
 * One file attached to a message: an image (downscaled copy sent as a
 * data-URL) or a text-like file (inlined into the request as context).
 * [path] is the app-internal copy that survives process death.
 */
data class UiAttachment(
    val id: String,
    val kind: Kind,
    val displayName: String,
    val mime: String,
    val path: String,
    val sizeBytes: Long,
) {
    enum class Kind { IMAGE, TEXT }
}

sealed interface ChatChunk {
    data class Delta(val text: String) : ChatChunk

    /**
 * Real model reasoning streamed by reasoning-capable providers
     *; never synthesized by the app.
     */
    data class Reasoning(val text: String) : ChatChunk
    data object Done : ChatChunk

    /**
 * A generation failure. [error] carries the user-facing message
 * ([ProviderError.userMessage]) shown in the UI; [detail] is optional
 * provider diagnostics (e.g. the provider's own error text) — it is
 * deliberately not rendered in the UI, which always shows the enum's
 * message, and is kept for debugging and tests.
     */
    data class Failure(val error: ProviderError, val detail: String? = null) : ChatChunk
}

enum class ProviderError(val userMessage: String) {
    InvalidCredentials("API key was rejected. Check the key and try again."),
    RateLimited("Rate limit reached. Try again shortly."),
    NetworkUnavailable("Unable to connect. Check your network."),
    Timeout("Request timed out."),
    ServerError("Provider is unavailable."),
    InvalidResponse("Provider returned an invalid response."),
    Unknown("Something went wrong."),
}
