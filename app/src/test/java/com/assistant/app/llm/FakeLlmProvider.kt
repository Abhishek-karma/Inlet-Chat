package com.assistant.app.llm

import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * One scripted step of [FakeLlmProvider].
 */
sealed interface ScriptedEvent {
    data class Delay(val ms: Long) : ScriptedEvent
    data class Emit(val text: String) : ScriptedEvent

    /** A reasoning delta, streamed like content. */
    data class Reasoning(val text: String) : ScriptedEvent
    data class Fail(val error: ProviderError) : ScriptedEvent
}

/**
 * Scripted [LlmProvider] test fixture. Each stream replays [script] through a
 * channel with real suspension points ([delay] before each event, suspending
 * sends), so cancellation of the collecting coroutine is genuinely exercised.
 *
 * After the last scripted event the stream emits [ChatChunk.Done] and closes.
 * A [ScriptedEvent.Fail] emits [ChatChunk.Failure] and closes immediately.
 */
class FakeLlmProvider(var script: List<ScriptedEvent>) : LlmProvider {

        val requests = mutableListOf<ChatRequest>()

    override fun stream(request: ChatRequest): Flow<ChatChunk> = channelFlow {
        requests += request
        launch {
            for (event in script) {
                when (event) {
                    is ScriptedEvent.Delay -> delay(event.ms)
                    is ScriptedEvent.Emit -> send(ChatChunk.Delta(event.text))
                    is ScriptedEvent.Reasoning -> send(ChatChunk.Reasoning(event.text))
                    is ScriptedEvent.Fail -> {
                        send(ChatChunk.Failure(event.error))
                        return@launch
                    }
                }
            }
            send(ChatChunk.Done)
        }
    }
}
