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
        val saved = mutableMapOf<String, ReasoningConfig>()

        fun repository(chatLlm: MutableStateFlow<ChatLlmState>, dispatcher: CoroutineDispatcher): ChatRepository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = dispatcher,
            loadThinkSelection = { model -> saved[model] },
            saveThinkSelection = { model, config -> saved[model] = config },
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
                thinkCapability = capability,
            ),
        )
        val repository = SelectionStore().repository(chatLlm, mainDispatcher)
        // The ViewModel applies the active capability to repository state the
        // same way when the provider changes; mirror that here.
        repository.onThinkModelChanged("my-custom-reasoning-model", capability)
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
        val chatLlm = MutableStateFlow<ChatLlmState>(
            ChatLlmState.Ready(
                provider = FakeLlmProvider(helloScript),
                model = "gemini-2.5-pro",
                thinkCapability = ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true),
            ),
        )
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = SelectionStore()
        val repository = store.repository(chatLlm, mainDispatcher)
        repository.onThinkModelChanged("gemini-2.5-pro", ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true))
        repository.setThinkConfig(ReasoningConfig.Budget(8192))
        assertEquals(ReasoningConfig.Budget(8192), store.saved["gemini-2.5-pro"])

        // A stored effort selection is dropped on a budget-only capability.
        store.saved["gemini-2.5-pro"] = ReasoningConfig.Effort(ReasoningEffort.HIGH)
        val clamped = store.repository(chatLlm, mainDispatcher)
        clamped.onThinkModelChanged("gemini-2.5-pro", ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true))
        assertEquals(ReasoningConfig.Auto, clamped.uiState.value.thinkConfig)
    }
}
