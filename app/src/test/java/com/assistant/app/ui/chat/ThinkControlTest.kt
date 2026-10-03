package com.assistant.app.ui.chat

import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ReasoningEffort
import com.assistant.app.llm.model.thinkCapabilityFor
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
 * Model-aware Think control behavior in [ChatRepository]: capability-driven
 * selection, capture of the reasoning config when a generation starts, no
 * mutation of an active request, unsupported-model safety, and persistence
 * round-trips.
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
        model: String,
        script: List<ScriptedEvent>,
        block: suspend TestScope.(ChatRepository, FakeLlmProvider, MutableStateFlow<ChatLlmState>) -> Unit,
    ): TestResult = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(script)
        val chatLlm = MutableStateFlow<ChatLlmState>(
            ChatLlmState.Ready(
                provider = provider,
                model = model,
                thinkCapability = thinkCapabilityFor(model.startsWith("gemini"), model),
            ),
        )
        val repository = SelectionStore().repository(chatLlm, mainDispatcher)
        block(repository, provider, chatLlm)
    }

    private val helloScript = listOf(
        ScriptedEvent.Emit("Hello"),
        ScriptedEvent.Delay(50),
    )

    @Test
    fun `generation carries the selected reasoning config`() = thinkRunTest(
        model = "o3-mini",
        script = helloScript,
    ) { repository, provider, chatLlm ->
        repository.onThinkModelChanged("o3-mini", (chatLlm.value as ChatLlmState.Ready).thinkCapability)
        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.HIGH))

        launch { repository.send("Hi") }
        advanceUntilIdle()

        assertEquals(ReasoningConfig.Effort(ReasoningEffort.HIGH), provider.requests.single().reasoning)
    }

    @Test
    fun `auto selection sends no reasoning config`() = thinkRunTest(
        model = "o3-mini",
        script = helloScript,
    ) { repository, provider, chatLlm ->
        repository.onThinkModelChanged("o3-mini", (chatLlm.value as ChatLlmState.Ready).thinkCapability)

        launch { repository.send("Hi") }
        advanceUntilIdle()

        assertNull(provider.requests.single().reasoning)
    }

    @Test
    fun `unsupported model ignores selection and sends no reasoning config`() = thinkRunTest(
        model = "llama3.1:8b",
        script = helloScript,
    ) { repository, provider, chatLlm ->
        repository.onThinkModelChanged("llama3.1:8b", (chatLlm.value as ChatLlmState.Ready).thinkCapability)
        repository.setThinkConfig(ReasoningConfig.Budget(4096))
        assertEquals(ReasoningConfig.Auto, repository.uiState.value.thinkConfig)

        launch { repository.send("Hi") }
        advanceUntilIdle()

        assertNull(provider.requests.single().reasoning)
    }

    @Test
    fun `changing the selection while streaming does not mutate the active request`() = thinkRunTest(
        model = "gemini-2.5-flash",
        script = listOf(
            ScriptedEvent.Delay(500),
            ScriptedEvent.Emit("Hello"),
            ScriptedEvent.Delay(500),
        ),
    ) { repository, provider, chatLlm ->
        repository.onThinkModelChanged("gemini-2.5-flash", (chatLlm.value as ChatLlmState.Ready).thinkCapability)
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
        model = "o3-mini",
        script = listOf(ScriptedEvent.Fail(com.assistant.app.llm.model.ProviderError.ServerError)),
    ) { repository, provider, chatLlm ->
        repository.onThinkModelChanged("o3-mini", (chatLlm.value as ChatLlmState.Ready).thinkCapability)
        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.LOW))
        launch { repository.send("Hi") }
        advanceUntilIdle()

        repository.setThinkConfig(ReasoningConfig.Effort(ReasoningEffort.HIGH))
        launch { repository.retry() }
        advanceUntilIdle()

        assertEquals(2, provider.requests.size)
        assertEquals(
            ReasoningConfig.Effort(ReasoningEffort.HIGH),
            provider.requests[1].reasoning,
        )
    }

    @Test
    fun `selection persists per model and is restored clamped to the model capability`() = runTest {
        val chatLlm = MutableStateFlow<ChatLlmState>(
            ChatLlmState.Ready(
                provider = FakeLlmProvider(helloScript),
                model = "gemini-2.5-pro",
                thinkCapability = thinkCapabilityFor(true, "gemini-2.5-pro"),
            ),
        )
        val store = SelectionStore()
        val repository = store.repository(chatLlm, UnconfinedTestDispatcher(testScheduler))
        repository.onThinkModelChanged("gemini-2.5-pro", (chatLlm.value as ChatLlmState.Ready).thinkCapability)
        repository.setThinkConfig(ReasoningConfig.Budget(8192))

        assertEquals(ReasoningConfig.Budget(8192), store.saved["gemini-2.5-pro"])

        // A fresh repository (process-death path) restores the saved value.
        val reloaded = store.repository(chatLlm, UnconfinedTestDispatcher(testScheduler))
        reloaded.onThinkModelChanged("gemini-2.5-pro", (chatLlm.value as ChatLlmState.Ready).thinkCapability)
        assertEquals(ReasoningConfig.Budget(8192), reloaded.uiState.value.thinkConfig)

        // A stored effort selection is dropped on a budget-only model.
        store.saved["gemini-2.5-pro"] = ReasoningConfig.Effort(ReasoningEffort.HIGH)
        val clamped = store.repository(chatLlm, UnconfinedTestDispatcher(testScheduler))
        clamped.onThinkModelChanged("gemini-2.5-pro", (chatLlm.value as ChatLlmState.Ready).thinkCapability)
        assertEquals(ReasoningConfig.Auto, clamped.uiState.value.thinkConfig)
    }
}
