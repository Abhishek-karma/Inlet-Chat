package com.assistant.app.ui.chat

import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ReasoningEffort
import com.assistant.app.llm.model.ThinkCapability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Capability-driven Think behavior in [ChatRepository]. Capabilities are
 * passed in as explicit values (never derived from model names), so an
 * arbitrary model id with an unknown/unsupported capability never produces a
 * reasoning parameter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThinkControlTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class SelectionStore {
        val saved = mutableMapOf<Pair<Long, Long>, ReasoningConfig>()

        fun repository(chatLlm: MutableStateFlow<ChatLlmState>, dispatcher: CoroutineDispatcher): ChatRepository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = dispatcher,
            loadThinkSelection = { providerId, modelId -> saved[providerId to modelId] },
            saveThinkSelection = { providerId, modelId, config -> saved[providerId to modelId] = config },
        )
    }

    private fun thinkRunTest(
        capability: ThinkCapability,
        script: List<ScriptedEvent>,
        block: suspend TestScope.(ChatRepository, FakeLlmProvider, MutableStateFlow<ChatLlmState>) -> Unit,
    ): TestResult = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(script)
        val chatLlm = MutableStateFlow<ChatLlmState>(
            ChatLlmState.Ready(
                provider = provider,
                model = "my-custom-reasoning-model",
                providerId = 1L,
                modelId = 10L,
                thinkCapability = capability,
            ),
        )
        val repository = SelectionStore().repository(chatLlm, mainDispatcher)
        // The ViewModel applies the active capability to repository state the
        // same way when the provider/model changes; mirror that here.
        repository.onThinkModelChanged(1L, 10L, capability)
        block(repository, provider, chatLlm)
    }

    private val helloScript = listOf(
        ScriptedEvent.Emit("Hello"),
        ScriptedEvent.Delay(50),
    )

    @Test
    fun `unknown capability sends no reasoning config even with a selection`() = thinkRunTest(
        capability = ThinkCapability.Unknown,
        script = helloScript,
    ) { repository, provider, _ ->
        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.HIGH))
        assertEquals(ReasoningConfig.Auto, repository.uiState.value.thinkConfig)

        launch { repository.send("Hi") }
        advanceUntilIdle()

        assertNull(provider.requests.single().reasoning)
    }

    @Test
    fun `unsupported capability sends no reasoning config`() = thinkRunTest(
        capability = ThinkCapability.Unsupported,
        script = helloScript,
    ) { repository, provider, _ ->
        repository.setThinkConfig(ReasoningConfig.Budget(4096))
        assertEquals(ReasoningConfig.Auto, repository.uiState.value.thinkConfig)

        launch { repository.send("Hi") }
        advanceUntilIdle()

        assertNull(provider.requests.single().reasoning)
    }

    @Test
    fun `effort capability carries the selected effort`() = thinkRunTest(
        capability = ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
        script = helloScript,
    ) { repository, provider, _ ->
        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.HIGH))
        launch { repository.send("Hi") }
        advanceUntilIdle()
        assertEquals(ReasoningConfig.Effort(ReasoningEffort.HIGH), provider.requests.single().reasoning)
    }

    @Test
    fun `auto selection sends no reasoning config`() = thinkRunTest(
        capability = ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
        script = helloScript,
    ) { repository, provider, _ ->
        launch { repository.send("Hi") }
        advanceUntilIdle()
        assertNull(provider.requests.single().reasoning)
    }

    @Test
    fun `budget capability carries the selected budget`() = thinkRunTest(
        capability = ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true),
        script = helloScript,
    ) { repository, provider, _ ->
        repository.setThinkConfig(ReasoningConfig.Budget(4096))
        launch { repository.send("Hi") }
        advanceUntilIdle()
        assertEquals(ReasoningConfig.Budget(4096), provider.requests.single().reasoning)
    }

    @Test
    fun `changing the selection while streaming does not mutate the active request`() = thinkRunTest(
        capability = ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true),
        script = listOf(
            ScriptedEvent.Delay(500),
            ScriptedEvent.Emit("Hello"),
            ScriptedEvent.Delay(500),
        ),
    ) { repository, provider, _ ->
        repository.setThinkConfig(ReasoningConfig.Budget(4096))

        launch { repository.send("Hi") }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(ChatStatus.Generating, repository.uiState.value.status)

        repository.setThinkConfig(ReasoningConfig.Budget(16384))
        assertEquals(ReasoningConfig.Budget(4096), provider.requests.single().reasoning)

        repository.stop()
        advanceUntilIdle()
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `retry uses the selection current at retry time`() = thinkRunTest(
        capability = ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
        script = listOf(ScriptedEvent.Fail(ProviderError.ServerError)),
    ) { repository, provider, _ ->
        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.LOW))
        launch { repository.send("Hi") }
        advanceUntilIdle()

        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.HIGH))
        launch { repository.retry() }
        advanceUntilIdle()

        assertEquals(2, provider.requests.size)
        assertEquals(ReasoningConfig.Effort(ReasoningEffort.HIGH), provider.requests[1].reasoning)
    }

    @Test
    fun `persisted selection is validated against the model capability`() = runTest {
        val capability = ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true)
        val chatLlm = MutableStateFlow<ChatLlmState>(
            ChatLlmState.Ready(
                provider = FakeLlmProvider(helloScript),
                model = "local-model-7b",
                providerId = 1L,
                modelId = 10L,
                thinkCapability = capability,
            ),
        )
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = SelectionStore()
        val repository = store.repository(chatLlm, mainDispatcher)
        repository.onThinkModelChanged(1L, 10L, capability)
        repository.setThinkConfig(ReasoningConfig.Budget(8192))
        assertEquals(ReasoningConfig.Budget(8192), store.saved[1L to 10L])

        // The same model id under another provider keeps its own Think state.
        store.saved[2L to 10L] = ReasoningConfig.Off
        assertEquals(ReasoningConfig.Off, store.repository(chatLlm, mainDispatcher).let {
            store.saved[2L to 10L]
        })

        // A stored effort selection is dropped on a budget-only capability.
        store.saved[1L to 10L] = ReasoningConfig.Effort(ReasoningEffort.HIGH)
        val clamped = store.repository(chatLlm, mainDispatcher)
        clamped.onThinkModelChanged(1L, 10L, capability)
        assertEquals(ReasoningConfig.Auto, clamped.uiState.value.thinkConfig)
    }

    @Test
    fun `switching the active model updates capability and validates the selection`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        // Two models of the same provider: one declared BUDGET, one unspecified.
        val budgetReady = ChatLlmState.Ready(
            provider = FakeLlmProvider(helloScript),
            model = "alpha-model",
            providerId = 1L,
            modelId = 10L,
            thinkCapability = ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true),
        )
        val unknownReady = ChatLlmState.Ready(
            provider = FakeLlmProvider(helloScript),
            model = "alpha-model",
            providerId = 1L,
            modelId = 11L,
            thinkCapability = ThinkCapability.Unknown,
        )
        val chatLlm = MutableStateFlow<ChatLlmState>(budgetReady)
        val store = SelectionStore()
        val repository = store.repository(chatLlm, dispatcher)

        repository.onThinkModelChanged(1L, 10L, budgetReady.thinkCapability)
        repository.setThinkConfig(ReasoningConfig.Budget(4096))
        assertEquals(ReasoningConfig.Budget(4096), repository.uiState.value.thinkConfig)

        // Switching to the unspecified model resets the selection: nothing is sent.
        repository.onThinkModelChanged(1L, 11L, unknownReady.thinkCapability)
        assertEquals(ReasoningConfig.Auto, repository.uiState.value.thinkConfig)
        launch { repository.send("Hi") }
        advanceUntilIdle()
        assertNull(chatLlm.value.let { _ -> FakeLlmProvider(helloScript) }.requests.lastOrNull()?.reasoning)

        // Switching back restores this model's own saved selection.
        repository.onThinkModelChanged(1L, 10L, budgetReady.thinkCapability)
        assertEquals(ReasoningConfig.Budget(4096), repository.uiState.value.thinkConfig)
    }

    @Test
    fun `same model id under different providers keeps separate think state`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val capability = ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        val chatLlm = MutableStateFlow<ChatLlmState>(
            ChatLlmState.Ready(
                provider = FakeLlmProvider(helloScript),
                model = "model-x",
                providerId = 1L,
                modelId = 5L,
                thinkCapability = capability,
            ),
        )
        val store = SelectionStore()
        val repository = store.repository(chatLlm, dispatcher)
        repository.onThinkModelChanged(1L, 5L, capability)
        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.LOW))
        assertEquals(ReasoningConfig.Effort(ReasoningEffort.LOW), store.saved[1L to 5L])

        // Same model id, other provider: no selection stored yet.
        val other = store.repository(chatLlm, dispatcher)
        other.onThinkModelChanged(2L, 5L, capability)
        assertEquals(ReasoningConfig.Auto, other.uiState.value.thinkConfig)
        assertNull(store.saved[2L to 5L])
    }
}
