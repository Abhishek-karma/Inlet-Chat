package com.assistant.app.llm

import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import kotlinx.coroutines.flow.Flow

/**
 * Streams a chat completion as [ChatChunk]s. Implementations must be safe to
 * collect once per request; cancelling the collector cancels the stream.
 */
interface LlmProvider {
    fun stream(request: ChatRequest): Flow<ChatChunk>
}
