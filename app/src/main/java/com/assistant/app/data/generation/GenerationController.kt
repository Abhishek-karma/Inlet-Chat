package com.assistant.app.data.generation

import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Encapsulates active generation session details.
 */
data class GenerationSession(
    val generationId: String,
    val conversationId: String,
    val assistantId: String,
    val job: Job,
)

/**
 * Owns the execution lifecycle of in-flight LLM streaming requests.
 * Guarantees single-generation ownership, prevents concurrent streams,
 * and isolates streaming callbacks to the matching conversation session.
 */
class GenerationController(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var activeSession: GenerationSession? = null
    private var lastPersistAt: Long = 0L

    val isGenerating: Boolean
        get() = activeSession?.job?.isActive == true

    val activeAssistantId: String?
        get() = activeSession?.takeIf { it.job.isActive }?.assistantId

    val activeConversationId: String?
        get() = activeSession?.takeIf { it.job.isActive }?.conversationId

    /**
     * Attempts to claim single-owner rights for a new generation.
     * Returns a new [GenerationSession] if successful, or null if another generation is active.
     */
    suspend fun tryStartSession(
        conversationId: String,
        assistantId: String,
        job: Job,
    ): GenerationSession? = mutex.withLock {
        if (isGenerating) {
            return null
        }
        val session = GenerationSession(
            generationId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            assistantId = assistantId,
            job = job,
        )
        activeSession = session
        lastPersistAt = clock()
        return session
    }

    fun stop() {
        activeSession?.job?.cancel()
    }

    suspend fun joinActive() {
        val job = activeSession?.job
        job?.join()
    }

    /**
     * Executes the stream against [provider] with [request] under [session].
     * Callbacks are guarded so stale/cancelled sessions do not update UI or persist cross-conversation.
     */
    suspend fun runStream(
        session: GenerationSession,
        provider: LlmProvider,
        request: ChatRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onPersist: suspend () -> Unit,
        onCancelled: suspend () -> Unit,
        onFailure: suspend (ChatChunk.Failure) -> Unit,
        onSuccess: suspend () -> Unit,
    ) {
        var failure: ChatChunk.Failure? = null
        try {
            withContext(dispatcher) {
                provider.stream(request).collect { chunk ->
                    if (!isSessionValid(session.generationId)) return@collect
                    when (chunk) {
                        is ChatChunk.Delta -> {
                            onDelta(chunk.text)
                            maybePersist(session.generationId, onPersist)
                        }
                        is ChatChunk.Reasoning -> {
                            onReasoning(chunk.text)
                            maybePersist(session.generationId, onPersist)
                        }
                        is ChatChunk.Done -> Unit
                        is ChatChunk.Failure -> failure = chunk
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                onPersist()
                onCancelled()
            }
            clearSession(session.generationId)
            throw e
        } catch (e: Exception) {
            failure = ChatChunk.Failure(ProviderError.Unknown, e.message)
        }

        val result = failure
        if (result == null) {
            clearSession(session.generationId)
            onPersist()
            onSuccess()
        } else {
            clearSession(session.generationId)
            onFailure(result)
        }
    }

    private fun isSessionValid(generationId: String): Boolean {
        val current = activeSession
        return current != null && current.generationId == generationId && current.job.isActive
    }

    private suspend fun clearSession(generationId: String) {
        mutex.withLock {
            if (activeSession?.generationId == generationId) {
                activeSession = null
            }
        }
    }

    private suspend fun maybePersist(generationId: String, onPersist: suspend () -> Unit) {
        val timestamp = clock()
        if (timestamp - lastPersistAt >= PERSIST_THROTTLE_MS) {
            lastPersistAt = timestamp
            if (isSessionValid(generationId)) {
                onPersist()
            }
        }
    }

    companion object {
        const val PERSIST_THROTTLE_MS = 300L
    }
}
