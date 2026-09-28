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
import kotlinx.coroutines.withContext

/**
 * Owns the execution lifecycle of in-flight LLM streaming requests:
 * connection, streaming chunk collection, persistence throttling, cancellation, and error handling.
 */
class GenerationController(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var activeJob: Job? = null
    private var lastPersistAt: Long = 0L

    val isGenerating: Boolean get() = activeJob?.isActive == true

    fun attachJob(job: Job) {
        activeJob = job
    }

    fun stop() {
        activeJob?.cancel()
    }

    suspend fun joinActive() {
        activeJob?.join()
    }

    /**
     * Executes the stream against [provider] with [request].
     * Throttles persistence callbacks and guarantees proper cleanup on cancellation.
     */
    suspend fun runStream(
        provider: LlmProvider,
        request: ChatRequest,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        onPersist: suspend () -> Unit,
        onCancelled: suspend () -> Unit,
        onFailure: suspend (ChatChunk.Failure) -> Unit,
        onSuccess: suspend () -> Unit,
    ) {
        val job = currentCoroutineContext().job
        activeJob = job
        lastPersistAt = clock()

        var failure: ChatChunk.Failure? = null
        try {
            withContext(dispatcher) {
                provider.stream(request).collect { chunk ->
                    when (chunk) {
                        is ChatChunk.Delta -> {
                            onDelta(chunk.text)
                            maybePersist(onPersist)
                        }
                        is ChatChunk.Reasoning -> {
                            onReasoning(chunk.text)
                            maybePersist(onPersist)
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
            throw e
        } catch (e: Exception) {
            failure = ChatChunk.Failure(ProviderError.Unknown, e.message)
        }

        val result = failure
        if (result == null) {
            if (activeJob === job) activeJob = null
            onPersist()
            onSuccess()
        } else {
            if (activeJob === job) activeJob = null
            onFailure(result)
        }
    }

    private suspend fun maybePersist(onPersist: suspend () -> Unit) {
        val timestamp = clock()
        if (timestamp - lastPersistAt >= PERSIST_THROTTLE_MS) {
            lastPersistAt = timestamp
            onPersist()
        }
    }

    companion object {
        const val PERSIST_THROTTLE_MS = 300L
    }
}
