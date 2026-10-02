package com.assistant.app

import androidx.lifecycle.viewModelScope
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.data.generation.GenerationController
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.model.Role
import com.assistant.app.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Send/stop/failure semantics of [ChatViewModel] + [ChatRepository] against a
 * scripted provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Runs [block] with a ViewModel wired to an in-memory repository and a
     * scripted provider, all on the shared virtual-time scheduler. `Main` is
     * replaced with an unconfined test dispatcher so `viewModelScope` launches
     * eagerly and generation interleaves deterministically with the test body.
     */
    private fun runChatTest(
        script: List<ScriptedEvent>,
        webSearch: (suspend (String) -> com.assistant.app.llm.model.SearchOutcome?)? = null,
        followUpSuggestions: (suspend (LlmProvider, String, String, String) -> List<String>)? = null,
        block: suspend TestScope.(ChatViewModel, FakeLlmProvider, ChatRepository) -> Unit,
    ) = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(script)
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val repository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = mainDispatcher,
            webSearch = webSearch,
            followUpSuggestions = followUpSuggestions,
        )
        block(ChatViewModel(repository, chatLlm), provider, repository)
    }

    /** Script that streams "Hel", "lo" then Done, with a pause before each event. */
    private val helloScript = listOf(
        ScriptedEvent.Delay(100),
        ScriptedEvent.Emit("Hel"),
        ScriptedEvent.Delay(100),
        ScriptedEvent.Emit("lo"),
        ScriptedEvent.Delay(100),
    )

    @Test
    fun `send appends messages, streams deltas in order, and returns to Idle`() =
        runChatTest(helloScript) { viewModel, provider, _ ->
            viewModel.send("Hi")

            val initial = viewModel.uiState.value
            assertEquals(2, initial.messages.size)
            assertEquals(Role.USER, initial.messages[0].role)
            assertEquals("Hi", initial.messages[0].content)
            assertEquals(Role.ASSISTANT, initial.messages[1].role)
            assertEquals("", initial.messages[1].content)
            assertEquals(ChatStatus.Generating, initial.status)
            assertEquals("", initial.draft)

            advanceTimeBy(100)
            runCurrent()
            assertEquals("Hel", viewModel.uiState.value.messages[1].content)

            advanceTimeBy(100)
            runCurrent()
            assertEquals("Hello", viewModel.uiState.value.messages[1].content)

            advanceTimeBy(100)
            advanceUntilIdle()

            assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
            assertEquals("Hello", viewModel.uiState.value.messages[1].content)
            assertEquals(1, provider.requests.size)
            assertEquals(listOf(Role.USER to "Hi"), provider.requests.single().messages)
        }

    @Test
    fun `send while generating is a no-op`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000)),
    ) { viewModel, provider, _ ->
        viewModel.send("First")
        viewModel.send("Second")

        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("First", viewModel.uiState.value.messages[0].content)
        assertEquals(ChatStatus.Generating, viewModel.uiState.value.status)
        assertEquals(1, provider.requests.size)

        advanceUntilIdle()
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun `stop cancels generation, keeps partial text, and returns to Idle`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000)),
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceTimeBy(100)
        runCurrent()
        assertEquals("Hel", viewModel.uiState.value.messages[1].content)

        viewModel.stop()
        runCurrent()

        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertEquals("Hel", viewModel.uiState.value.messages[1].content)
    }

    @Test
    fun aStalledPostCancellationFlushDoesNotHangCancellationForever() = runChatTest(
        script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000)),
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ChatStatus.Generating, viewModel.uiState.value.status)

        viewModel.stop()

        // The flush is bounded, so the scheduler must reach idle on its own
        // even though the stream would otherwise keep delaying forever.
        advanceTimeBy(GenerationController.CANCEL_FLUSH_TIMEOUT_MS * 3)
        runCurrent()
        advanceUntilIdle()

        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun `provider failure maps to user message and drops empty assistant placeholder`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(50), ScriptedEvent.Fail(ProviderError.InvalidCredentials)),
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Error(ProviderError.InvalidCredentials.userMessage), state.status)
        assertEquals(1, state.messages.size)
        assertEquals(Role.USER, state.messages[0].role)
        assertEquals("Hi", state.messages[0].content)
    }

    @Test
    fun `retry after error re-sends same user content once`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(50), ScriptedEvent.Fail(ProviderError.InvalidCredentials)),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        assertEquals(ChatStatus.Error(ProviderError.InvalidCredentials.userMessage), viewModel.uiState.value.status)

        provider.script = listOf(ScriptedEvent.Delay(50), ScriptedEvent.Emit("Hello"), ScriptedEvent.Delay(50))
        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertEquals(2, state.messages.size)
        assertEquals(Role.USER, state.messages[0].role)
        assertEquals("Hi", state.messages[0].content)
        assertEquals("Hello", state.messages[1].content)
        assertEquals(2, provider.requests.size)
        assertEquals(listOf(Role.USER to "Hi"), provider.requests[1].messages)
    }

    @Test
    fun `failed partial assistant message is excluded from subsequent send context`() = runChatTest(
        script = listOf(
            ScriptedEvent.Delay(50),
            ScriptedEvent.Emit("Partial answer before failure"),
            ScriptedEvent.Fail(ProviderError.ServerError)
        ),
    ) { viewModel, provider, _ ->
        viewModel.send("Question 1")
        advanceUntilIdle()

        assertEquals(ChatStatus.Error(ProviderError.ServerError.userMessage), viewModel.uiState.value.status)

        provider.script = listOf(
            ScriptedEvent.Emit("Clean answer")
        )
        viewModel.send("Question 2")
        advanceUntilIdle()

        val lastRequest = provider.requests.last()
        assertFalse(lastRequest.messages.any { it.second.contains("Partial answer before failure") })
    }

    @Test
    fun `regenerate keeps the message, versions the old answer, and re-requests from history`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val assistantId = viewModel.uiState.value.messages[1].id

        provider.script = listOf(ScriptedEvent.Emit("Hello again"))
        viewModel.regenerate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertEquals(2, state.messages.size)
        assertEquals(assistantId, state.messages[1].id)
        assertEquals("Hello again", state.messages[1].content)
        assertEquals(listOf("Hello", "Hello again"), state.messages[1].versions)
        assertEquals(1, state.messages[1].selectedVersion)
        assertEquals(2, provider.requests.size)
        assertEquals(listOf(Role.USER to "Hi"), provider.requests[1].messages)
    }

    @Test
    fun `switching versions shows the selected answer and continues from it`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val assistantId = viewModel.uiState.value.messages[1].id
        provider.script = listOf(ScriptedEvent.Emit("Hello again"))
        viewModel.regenerate()
        advanceUntilIdle()

        viewModel.switchVersion(assistantId, 0)
        advanceUntilIdle()
        assertEquals("Hello", viewModel.uiState.value.messages[1].content)
        assertEquals(0, viewModel.uiState.value.messages[1].selectedVersion)

        provider.script = listOf(ScriptedEvent.Emit("Third"))
        viewModel.send("More")
        advanceUntilIdle()
        assertEquals(
            listOf(Role.USER to "Hi", Role.ASSISTANT to "Hello", Role.USER to "More"),
            provider.requests[2].messages,
        )
    }

    @Test
    fun `stop during regeneration keeps the partial answer as a version`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val assistantId = viewModel.uiState.value.messages[1].id
        provider.script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000))
        viewModel.regenerate()
        advanceTimeBy(100)
        runCurrent()

        viewModel.stop()
        advanceUntilIdle()
        assertEquals("Hel", viewModel.uiState.value.messages[1].content)
        assertEquals(listOf("Hello", "Hel"), viewModel.uiState.value.messages[1].versions)
        assertEquals(1, viewModel.uiState.value.messages[1].selectedVersion)

        provider.script = listOf(ScriptedEvent.Emit("Hello again"))
        viewModel.regenerate()
        advanceUntilIdle()
        assertEquals(listOf("Hello", "Hel", "Hello again"), viewModel.uiState.value.messages[1].versions)
        assertEquals(assistantId, viewModel.uiState.value.messages[1].id)
    }


    private companion object {
        const val LONG_ANSWER = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }


    @Test
    fun `follow-up suggestions attach to a substantial answer`() = runChatTest(
        script = listOf(ScriptedEvent.Emit(LONG_ANSWER)),
        followUpSuggestions = { _, _, question, answer ->
            assertEquals("Hi", question)
            assertEquals(LONG_ANSWER, answer)
            listOf("What next?")
        },
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(listOf("What next?"), viewModel.uiState.value.messages[1].followUps)
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun `a short answer costs no extra request`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hi")),
        followUpSuggestions = { _, _, _, _ -> listOf("Should not be called") },
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(1, provider.requests.size)
        assertEquals(emptyList<String>(), viewModel.uiState.value.messages[1].followUps)
    }

    @Test
    fun `a failing suggestion request leaves the chat clean`() = runChatTest(
        script = listOf(ScriptedEvent.Emit(LONG_ANSWER)),
        followUpSuggestions = { _, _, _, _ -> throw IllegalStateException("boom") },
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertEquals(emptyList<String>(), viewModel.uiState.value.messages[1].followUps)
    }

    @Test
    fun `attachment limits are enforced on staged attachments`() = runChatTest(
        script = emptyList(),
    ) { _, _, repository ->
        val image = { n: Int ->
            com.assistant.app.llm.model.UiAttachment(
                id = "i$n",
                kind = com.assistant.app.llm.model.UiAttachment.Kind.IMAGE,
                displayName = "i$n.jpg",
                mime = "image/jpeg",
                path = "/nonexistent$n",
                sizeBytes = 1,
            )
        }
        repository.addPendingAttachments((1..4).map(image))
        assertEquals(4, repository.uiState.value.pendingAttachments.size)
        assertNull(repository.uiState.value.attachmentError)

        repository.addPendingAttachments(listOf(image(5)))

        assertEquals(4, repository.uiState.value.pendingAttachments.size)
        assertEquals("Up to 4 images per message.", repository.uiState.value.attachmentError)
    }

    @Test
    fun `send with staged attachments and blank draft is allowed`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("I see it")),
    ) { viewModel, _, repository ->
        repository.addPendingAttachments(
            listOf(
                com.assistant.app.llm.model.UiAttachment(
                    id = "a1",
                    kind = com.assistant.app.llm.model.UiAttachment.Kind.IMAGE,
                    displayName = "i.jpg",
                    mime = "image/jpeg",
                    path = "/nonexistent",
                    sizeBytes = 1,
                ),
            ),
        )

        viewModel.send("")
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.messages.size)
        assertEquals("", viewModel.uiState.value.messages[0].content)
        assertEquals(1, viewModel.uiState.value.messages[0].attachments.size)
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertEquals(emptyList<com.assistant.app.llm.model.UiAttachment>(), viewModel.uiState.value.pendingAttachments)
    }

    @Test
    fun `reasoning streams into the message and clears on regenerate`() = runChatTest(
        script = listOf(ScriptedEvent.Reasoning("thinking"), ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val assistantId = viewModel.uiState.value.messages[1].id
        assertEquals("thinking", viewModel.uiState.value.messages[1].reasoning)

        provider.script = listOf(ScriptedEvent.Emit("Hello again"))
        viewModel.regenerate()
        advanceUntilIdle()

        assertEquals("Hello again", viewModel.uiState.value.messages[1].content)
        assertEquals("", viewModel.uiState.value.messages[1].reasoning)
        assertEquals(listOf("Hello", "Hello again"), viewModel.uiState.value.messages[1].versions)
    }

    @Test
    fun `switching versions clears reasoning`() = runChatTest(
        script = listOf(ScriptedEvent.Reasoning("thinking"), ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val assistantId = viewModel.uiState.value.messages[1].id
        provider.script = listOf(ScriptedEvent.Emit("Hello again"))
        viewModel.regenerate()
        advanceUntilIdle()

        viewModel.switchVersion(assistantId, 0)
        advanceUntilIdle()

        assertEquals("Hello", viewModel.uiState.value.messages[1].content)
        assertEquals("", viewModel.uiState.value.messages[1].reasoning)
    }

    @Test
    fun `editAndResend truncates conversation at the edited message and re-requests`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("First reply")),
    ) { viewModel, provider, _ ->
        viewModel.send("First")
        advanceUntilIdle()
        provider.script = listOf(ScriptedEvent.Emit("Second reply"))
        viewModel.send("Second")
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.messages.size)
        val firstUserId = viewModel.uiState.value.messages[0].id

        provider.script = listOf(ScriptedEvent.Emit("Edited reply"))
        viewModel.setDraft("Edited")
        viewModel.editAndResend(firstUserId, "Edited")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertEquals("", state.draft)
        assertEquals(2, state.messages.size)
        assertEquals(firstUserId, state.messages[0].id)
        assertEquals("Edited", state.messages[0].content)
        assertEquals("Edited reply", state.messages[1].content)
        assertEquals(3, provider.requests.size)
        assertEquals(listOf(Role.USER to "Edited"), provider.requests[2].messages)
    }

    @Test
    fun `streaming updates assistant message in place with stable id`() = runChatTest(
        helloScript,
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceTimeBy(100)
        runCurrent()
        assertEquals("Hel", viewModel.uiState.value.messages[1].content)
        val idAfterFirstDelta = viewModel.uiState.value.messages[1].id
        val sizeAfterFirstDelta = viewModel.uiState.value.messages.size

        advanceTimeBy(100)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(idAfterFirstDelta, state.messages[1].id)
        assertEquals(sizeAfterFirstDelta, state.messages.size)
        assertEquals("Hello", state.messages[1].content)

        advanceTimeBy(100)
        advanceUntilIdle()
        assertEquals(idAfterFirstDelta, viewModel.uiState.value.messages[1].id)
    }

    @Test
    fun `cancelling the view model scope leaves no stuck generation state`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000)),
    ) { viewModel, provider, repository ->
        viewModel.send("Hi")
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ChatStatus.Generating, viewModel.uiState.value.status)

        viewModel.viewModelScope.cancel()
        runCurrent()

        assertEquals(ChatStatus.Idle, repository.uiState.value.status)
        assertEquals("Hel", repository.uiState.value.messages[1].content)

        val replacement = ChatViewModel(
            repository,
            MutableStateFlow(ChatLlmState.Ready(provider, "test-model")),
        )
        provider.script = listOf(ScriptedEvent.Emit("Recovered"))
        replacement.send("Again")
        advanceUntilIdle()

        val state = repository.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertEquals("Recovered", state.messages.last().content)
        assertEquals(4, state.messages.size)
    }

    @Test
    fun `error status carries the provider user message`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(50), ScriptedEvent.Fail(ProviderError.RateLimited)),
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()

        val status = viewModel.uiState.value.status
        assertTrue(status is ChatStatus.Error)
        assertEquals(ProviderError.RateLimited.userMessage, (status as ChatStatus.Error).message)
    }

    @Test
    fun `dismissError clears the error status and keeps messages`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(50), ScriptedEvent.Fail(ProviderError.InvalidCredentials)),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        assertEquals(ChatStatus.Error(ProviderError.InvalidCredentials.userMessage), viewModel.uiState.value.status)

        viewModel.dismissError()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertEquals(1, state.messages.size)
        assertEquals("Hi", state.messages[0].content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `newConversation clears conversation and draft`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, _, _ ->
        viewModel.send("Hi")
        advanceUntilIdle()
        viewModel.setDraft("typed but not sent")

        viewModel.newConversation()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertNull(state.conversationId)
        assertEquals(0, state.messages.size)
        assertEquals("", state.draft)
    }

    @Test
    fun `needsSetup without a configured provider, cleared when one becomes ready`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.NeedsSetup)
        val provider = FakeLlmProvider(listOf(ScriptedEvent.Emit("Hello")))
        val repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher)
        val viewModel = ChatViewModel(repository, chatLlm)

        viewModel.send("Hi")
        advanceUntilIdle()

        val setupState = viewModel.uiState.value
        assertTrue(setupState.needsSetup)
        assertEquals(ChatStatus.Idle, setupState.status)
        assertEquals(0, setupState.messages.size)

        chatLlm.value = ChatLlmState.Ready(provider, "test-model")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.needsSetup)

        viewModel.send("Hi")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertFalse(state.needsSetup)
        assertEquals("Hello", state.messages[1].content)
    }

    @Test
    fun `newConversation during generation stops generation and resets screen`() = runChatTest(
        script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000)),
    ) { viewModel, provider, _ ->
        viewModel.send("Hi")
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ChatStatus.Generating, viewModel.uiState.value.status)
        viewModel.setDraft("in-flight draft")

        viewModel.newConversation()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(ChatStatus.Idle, state.status)
        assertNull(state.conversationId)
        assertEquals(0, state.messages.size)
        assertEquals("", state.draft)

        provider.script = listOf(ScriptedEvent.Emit("Again"))
        viewModel.send("Next")
        advanceUntilIdle()
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertEquals("Again", viewModel.uiState.value.messages.last().content)
    }

    @Test
    fun `retry with degraded provider leaves the conversation untouched`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(listOf(ScriptedEvent.Fail(ProviderError.Unknown)))
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher)
        val viewModel = ChatViewModel(repository, chatLlm)

        viewModel.send("Hi")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.status is ChatStatus.Error)
        assertEquals(1, viewModel.uiState.value.messages.size)

        chatLlm.value = ChatLlmState.NeedsSetup
        viewModel.retry()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.needsSetup)
        assertTrue(state.status is ChatStatus.Error)
        assertEquals(1, state.messages.size)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `regenerate with degraded provider leaves the conversation untouched`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(listOf(ScriptedEvent.Emit("Hello")))
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher)
        val viewModel = ChatViewModel(repository, chatLlm)

        viewModel.send("Hi")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.messages.size)

        chatLlm.value = ChatLlmState.NeedsSetup
        viewModel.regenerate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.needsSetup)
        assertEquals(2, state.messages.size)
        assertEquals("Hello", state.messages[1].content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `staged attachments do not cross conversation boundaries`() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, _, repository ->
        repository.addPendingAttachments(
            listOf(
                com.assistant.app.llm.model.UiAttachment(
                    id = "a1",
                    kind = com.assistant.app.llm.model.UiAttachment.Kind.IMAGE,
                    displayName = "i.jpg",
                    mime = "image/jpeg",
                    path = "/nonexistent",
                    sizeBytes = 1,
                ),
            ),
        )

        viewModel.newConversation()

        assertEquals(
            emptyList<com.assistant.app.llm.model.UiAttachment>(),
            viewModel.uiState.value.pendingAttachments,
        )

        viewModel.send("Hi")
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.messages[0].attachments.size)
    }

    @Test
    fun `share text builder produces a readable transcript`() {
        val messages = listOf(
            com.assistant.app.llm.model.UiMessage("1", Role.USER, "Hi", 0),
            com.assistant.app.llm.model.UiMessage("2", Role.ASSISTANT, "Hello", 1),
            com.assistant.app.llm.model.UiMessage("3", Role.ASSISTANT, "", 2),
        )
        val text = ChatRepository.buildShareText("Title", messages)
        assertEquals("Title\n\nYou: Hi\n\nAssistant: Hello", text)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runSearchTest(
        script: List<ScriptedEvent>,
        webSearch: suspend (String) -> com.assistant.app.llm.model.SearchOutcome?,
        followUpSuggestions: (suspend (LlmProvider, String, String, String) -> List<String>)? = null,
        block: suspend TestScope.(ChatViewModel, FakeLlmProvider, ChatRepository) -> Unit,
    ) = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(script)
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val repository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = mainDispatcher,
            webSearch = webSearch,
            followUpSuggestions = followUpSuggestions,
        )
        block(ChatViewModel(repository, chatLlm), provider, repository)
    }

    @Test
    fun `enabled search injects results and shows sources`() = runSearchTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
        webSearch = { query ->
            assertEquals("Kotlin coroutines", query)
            com.assistant.app.llm.model.SearchOutcome.Success(
                listOf(
                    com.assistant.app.llm.model.SearchResult("Coroutines guide", "https://kotlinlang.org/coroutines", "async stuff"),
                ),
            )
        },
    ) { viewModel, provider, repository ->
        repository.setSearchEnabled(true)
        viewModel.send("Kotlin coroutines")
        advanceUntilIdle()

        val request = provider.requests.first { it.messages.any { m -> m.second.contains("[Web results]") } }
        assertTrue(request.messages.last().second.contains("Coroutines guide"))
        assertTrue(request.messages.last().second.contains("https://kotlinlang.org/coroutines"))
        val sources = viewModel.uiState.value.messages.last().sources
        assertEquals(listOf("https://kotlinlang.org/coroutines"), sources.map { it.url })
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.searchNotice)
    }

    @Test
    fun `failed search proceeds with a notice`() = runSearchTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
        webSearch = { _ ->
            com.assistant.app.llm.model.SearchOutcome.Failure(com.assistant.app.llm.model.SearchError.NetworkUnavailable)
        },
    ) { viewModel, provider, repository ->
        repository.setSearchEnabled(true)
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertEquals(
            com.assistant.app.llm.model.SearchError.NetworkUnavailable.userMessage,
            viewModel.uiState.value.searchNotice,
        )
        assertEquals(emptyList<com.assistant.app.llm.model.SearchResult>(), viewModel.uiState.value.messages.last().sources)
        assertTrue(provider.requests.single().messages.last().second.contains("Hi"))
    }

    @Test
    fun `throwing search service proceeds with a notice`() = runSearchTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
        webSearch = { _ -> throw IllegalStateException("hostile search service") },
    ) { viewModel, provider, repository ->
        repository.setSearchEnabled(true)
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertEquals(
            com.assistant.app.llm.model.SearchError.Unknown.userMessage,
            viewModel.uiState.value.searchNotice,
        )
        assertEquals("Hello", viewModel.uiState.value.messages.last().content)
    }

    @Test
    fun `search disabled sends without web context`() = runSearchTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
        webSearch = { _ ->
            throw IllegalStateException("search must not run while disabled")
        },
    ) { viewModel, provider, repository ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertTrue(provider.requests.single().messages.last().second.contains("Hi"))
    }

    @Test
    fun `the user message appears before a slow search finishes`() = runSearchTest(
        script = listOf(ScriptedEvent.Delay(1_000), ScriptedEvent.Emit("Hello")),
        webSearch = { _ ->
            delay(1_000)
            com.assistant.app.llm.model.SearchOutcome.Success(
                listOf(
                    com.assistant.app.llm.model.SearchResult("Guide", "https://kotlinlang.org", "async stuff"),
                ),
            )
        },
    ) { viewModel, provider, repository ->
        repository.setSearchEnabled(true)
        viewModel.send("Kotlin coroutines")
        runCurrent()

        // The search is still in flight, but the turn is already on screen.
        val shown = viewModel.uiState.value.messages
        assertEquals("Kotlin coroutines", shown[0].content)
        assertEquals(Role.ASSISTANT, shown[1].role)
        assertTrue(provider.requests.isEmpty())

        advanceUntilIdle()
        assertEquals(
            listOf("https://kotlinlang.org"),
            viewModel.uiState.value.messages[0].webResults.map { it.url },
        )
    }

    @Test
    fun `a second send is ignored while the first search is still running`() = runSearchTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
        webSearch = { _ ->
            delay(1_000)
            com.assistant.app.llm.model.SearchOutcome.Success(emptyList())
        },
    ) { viewModel, provider, repository ->
        repository.setSearchEnabled(true)
        viewModel.send("First")
        runCurrent()
        viewModel.send("Second")
        advanceUntilIdle()

        val userTexts = viewModel.uiState.value.messages
            .filter { it.role == Role.USER }
            .map { it.content }
        assertEquals(listOf("First"), userTexts)
        assertEquals(1, provider.requests.size)
    }
}
