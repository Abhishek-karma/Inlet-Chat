package com.assistant.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.data.ConversationStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.Role
import com.assistant.app.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * End-to-end integration and persistence tests for the follow-up suggestion lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FollowUpIntegrationTest {

    @After
    fun tearDown() {
        database?.close()
        Dispatchers.resetMain()
    }

    private var database: ChatDatabase? = null

    private val directExecutor = Executor { it.run() }

    private fun runTestWithStore(
        script: List<ScriptedEvent>,
        followUpSuggestions: (suspend (LlmProvider, String, String, String) -> List<String>)? = null,
        block: suspend TestScope.(ChatViewModel, FakeLlmProvider, ChatRepository, ConversationStore) -> Unit,
    ): TestResult = runTest {
        val db = Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java)
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .allowMainThreadQueries()
            .build()
        database = db
        val store = ConversationStore(db)
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(script)
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val repository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = mainDispatcher,
            store = store,
            followUpSuggestions = followUpSuggestions,
            clock = { testScheduler.currentTime },
        )
        val viewModel = ChatViewModel(repository, chatLlm)
        block(viewModel, provider, repository, store)
    }

    @Test
    fun followUpGeneration_success() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Kotlin coroutines simplify asynchronous programming.")),
        followUpSuggestions = { _, _, question, answer ->
            assertEquals("What are coroutines?", question)
            assertEquals("Kotlin coroutines simplify asynchronous programming.", answer)
            listOf("How do dispatchers work?", "What is structured concurrency?")
        },
    ) { viewModel, _, _, _ ->
        viewModel.send("What are coroutines?")
        advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        assertEquals(2, messages.size)
        val assistant = messages[1]
        assertEquals(
            listOf("How do dispatchers work?", "What is structured concurrency?"),
            assistant.followUps,
        )
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun followUpGeneration_shortAnswer() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Paris is the capital of France.")),
        followUpSuggestions = { _, _, _, _ ->
            listOf("What is the population of Paris?", "What are top attractions in Paris?")
        },
    ) { viewModel, _, _, _ ->
        viewModel.send("What is the capital of France?")
        advanceUntilIdle()

        val assistant = viewModel.uiState.value.messages[1]
        assertEquals(2, assistant.followUps.size)
        assertEquals("What is the population of Paris?", assistant.followUps[0])
    }

    @Test
    fun followUpGeneration_emptyAnswer() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("")),
        followUpSuggestions = { _, _, _, _ ->
            listOf("Should not be called")
        },
    ) { viewModel, _, _, _ ->
        viewModel.send("Hello")
        advanceUntilIdle()

        val assistant = viewModel.uiState.value.messages.firstOrNull { it.role == Role.ASSISTANT }
        assertTrue(assistant == null || assistant.followUps.isEmpty())
    }

    @Test
    fun followUpGeneration_timeout() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Useful complete response from assistant.")),
        followUpSuggestions = { _, _, _, _ ->
            // Exceeds TIMEOUT_MS (6000ms)
            delay(10_000)
            listOf("Slow question?")
        },
    ) { viewModel, _, _, _ ->
        viewModel.send("Tell me something")
        advanceUntilIdle()

        val assistant = viewModel.uiState.value.messages[1]
        assertEquals("Useful complete response from assistant.", assistant.content)
        assertTrue(assistant.followUps.isEmpty())
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun followUpGeneration_providerFailure() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Successful main answer text.")),
        followUpSuggestions = { _, _, _, _ ->
            throw IllegalStateException("API quota exceeded for secondary request")
        },
    ) { viewModel, _, _, _ ->
        viewModel.send("Hello")
        advanceUntilIdle()

        val assistant = viewModel.uiState.value.messages[1]
        assertEquals("Successful main answer text.", assistant.content)
        assertTrue(assistant.followUps.isEmpty())
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun followUps_arePersisted() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Detailed answer about Room database.")),
        followUpSuggestions = { _, _, _, _ ->
            listOf("What is a DAO?", "How do migrations work?")
        },
    ) { viewModel, _, _, store ->
        viewModel.send("Explain Room")
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!
        val persistedAssistant = store.messages(conversationId).first().last { it.role == "ASSISTANT" }
        assertEquals("What is a DAO?\nHow do migrations work?", persistedAssistant.followUps)
    }

    @Test
    fun followUps_areRestored() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Detailed answer about Room database.")),
        followUpSuggestions = { _, _, _, _ ->
            listOf("What is a DAO?", "How do migrations work?")
        },
    ) { viewModel, provider, _, store ->
        viewModel.send("Explain Room")
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!

        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val restoredRepository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = UnconfinedTestDispatcher(testScheduler),
            store = store,
        )
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()

        val restoredAssistant = restoredViewModel.uiState.value.messages[1]
        assertEquals(
            listOf("What is a DAO?", "How do migrations work?"),
            restoredAssistant.followUps,
        )
    }

    @Test
    fun regeneration_clearsOldFollowUps() = runTestWithStore(
        script = listOf(
            ScriptedEvent.Emit("First answer."),
        ),
        followUpSuggestions = { _, _, _, answer ->
            if (answer == "First answer.") {
                listOf("Follow up 1?", "Follow up 2?")
            } else {
                emptyList()
            }
        },
    ) { viewModel, provider, _, store ->
        viewModel.send("Question")
        advanceUntilIdle()

        val initialAssistant = viewModel.uiState.value.messages[1]
        assertEquals(listOf("Follow up 1?", "Follow up 2?"), initialAssistant.followUps)

        provider.script = listOf(ScriptedEvent.Emit("Regenerated second answer."))
        viewModel.regenerate()
        advanceUntilIdle()

        val regeneratedAssistant = viewModel.uiState.value.messages[1]
        assertEquals("Regenerated second answer.", regeneratedAssistant.content)
        assertTrue(regeneratedAssistant.followUps.isEmpty())

        val conversationId = viewModel.uiState.value.conversationId!!
        val persisted = store.messages(conversationId).first().last { it.role == "ASSISTANT" }
        assertNull(persisted.followUps)
    }

    @Test
    fun retry_doesNotDuplicateFollowUps() = runTestWithStore(
        script = listOf(
            ScriptedEvent.Emit("Partial text before failure"),
            ScriptedEvent.Fail(com.assistant.app.llm.model.ProviderError.NetworkUnavailable),
        ),
        followUpSuggestions = { _, _, _, _ ->
            listOf("Follow up A?", "Follow up B?")
        },
    ) { viewModel, provider, _, store ->
        viewModel.send("Question")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.status is ChatStatus.Error)

        provider.script = listOf(ScriptedEvent.Emit("Complete retry answer."))
        viewModel.retry()
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!
        val persistedMessages = store.messages(conversationId).first()
        val assistantRows = persistedMessages.filter { it.role == "ASSISTANT" }
        assertEquals(1, assistantRows.size)
        assertEquals(listOf("Follow up A?", "Follow up B?"), viewModel.uiState.value.messages.last().followUps)
    }

    @Test
    fun conversationSwitch_doesNotLeakFollowUps() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Conversation 1 answer.")),
        followUpSuggestions = { _, _, _, answer ->
            if (answer.contains("Conversation 1")) {
                delay(200)
                listOf("Question for conv 1?")
            } else {
                listOf("Question for conv 2?")
            }
        },
    ) { viewModel, provider, repository, _ ->
        viewModel.send("Hello 1")
        advanceTimeBy(50)

        // Switch to new conversation while follow up is in flight
        viewModel.newConversation()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.messages.isEmpty())

        provider.script = listOf(ScriptedEvent.Emit("Conversation 2 answer."))
        viewModel.send("Hello 2")
        advanceUntilIdle()

        val conv2Assistant = viewModel.uiState.value.messages[1]
        assertEquals("Conversation 2 answer.", conv2Assistant.content)
        assertEquals(listOf("Question for conv 2?"), conv2Assistant.followUps)
    }

    @Test
    fun cancelledGeneration_doesNotWriteFollowUps() = runTestWithStore(
        script = listOf(
            ScriptedEvent.Emit("Streaming partial answer..."),
            ScriptedEvent.Delay(2_000),
        ),
        followUpSuggestions = { _, _, _, _ ->
            listOf("Cancelled follow-up?")
        },
    ) { viewModel, _, _, _ ->
        viewModel.send("Question")
        advanceTimeBy(100)
        viewModel.stop()
        advanceUntilIdle()

        val assistant = viewModel.uiState.value.messages[1]
        assertTrue(assistant.followUps.isEmpty())
    }

    @Test
    fun suggestionSelection_populatesDraft() = runTestWithStore(
        script = listOf(ScriptedEvent.Emit("Answer with follow-up.")),
        followUpSuggestions = { _, _, _, _ ->
            listOf("What about this?", "What about that?")
        },
    ) { viewModel, _, _, _ ->
        viewModel.send("Initial prompt")
        advanceUntilIdle()

        val suggestions = viewModel.uiState.value.messages[1].followUps
        assertEquals(2, suggestions.size)

        viewModel.setDraft(suggestions[0])
        assertEquals("What about this?", viewModel.uiState.value.draft)
    }
}
