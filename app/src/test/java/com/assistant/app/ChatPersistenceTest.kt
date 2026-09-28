package com.assistant.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.AttachmentIngester
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.data.ConversationStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.Role
import com.assistant.app.llm.model.UiMessage
import com.assistant.app.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import java.io.File
import java.util.concurrent.Executor

/**
 * Persistence behavior of [ChatRepository] through [ConversationStore]:
 * what lands in Room when messages are sent, streamed, stopped, edited, and
 * restored into a fresh repository (process-death path).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatPersistenceTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Runs Room's query and transaction work inline on the calling thread
     * (Robolectric is single-threaded), so suspend DAO calls complete
     * synchronously and no real threads race the assertions or outlive the
     * closed database.
     */
    private val directExecutor = Executor { it.run() }

    /**
     * Same wiring as [ChatViewModelTest], plus the real store and a virtual
     * clock tied to the test scheduler, so the 300 ms persistence throttle is
     * exercised deterministically without real sleeps.
     */
    private fun runChatTest(
        script: List<ScriptedEvent>,
        attachmentsDir: File? = null,
        block: suspend TestScope.(ChatViewModel, FakeLlmProvider, ChatRepository, ConversationStore) -> Unit,
    ): TestResult {
        val db = Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java)
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .allowMainThreadQueries()
            .build()
        val store = ConversationStore(db)
        try {
            return runTest {
                val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
                Dispatchers.setMain(mainDispatcher)
                val provider = FakeLlmProvider(script)
                val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
                val repository = ChatRepository(
                    chatLlm = chatLlm,
                    generationDispatcher = mainDispatcher,
                    store = store,
                    clock = { testScheduler.currentTime },
                    attachmentsDir = attachmentsDir,
                )
                block(ChatViewModel(repository, chatLlm), provider, repository, store)
            }
        } finally {
            db.close()
        }
    }

    private val helloScript = listOf(
        ScriptedEvent.Delay(100),
        ScriptedEvent.Emit("Hel"),
        ScriptedEvent.Delay(100),
        ScriptedEvent.Emit("lo"),
        ScriptedEvent.Delay(100),
    )

    private val secretMarker = "sk-secret-test-key-marker"

    @Test
    fun sendPersistsUserAndCompletedAssistantContent() = runChatTest(helloScript) { viewModel, _, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!
        val conversation = store.conversations().first().single()
        assertEquals(conversationId, conversation.id)
        assertEquals("Hi", conversation.title)

        val messages = store.messages(conversationId).first()
        assertEquals(2, messages.size)
        assertEquals("USER", messages[0].role)
        assertEquals("Hi", messages[0].content)
        assertEquals("ASSISTANT", messages[1].role)
        assertEquals("Hello", messages[1].content)
        assertEquals(viewModel.uiState.value.messages[1].id, messages[1].id)
    }

    @Test
    fun streamedContentPersistsAtThrottlePointsWhileGenerating() = runChatTest(
        script = listOf(
            ScriptedEvent.Delay(150),
            ScriptedEvent.Emit("Hel"),
            ScriptedEvent.Delay(150),
            ScriptedEvent.Emit("lo"),
            ScriptedEvent.Delay(100_000),
        ),
    ) { viewModel, _, _, store ->
        viewModel.send("Hi")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(ChatStatus.Generating, viewModel.uiState.value.status)

        val conversationId = viewModel.uiState.value.conversationId!!
        val persisted = store.messages(conversationId).first().last { it.role == "ASSISTANT" }.content
        assertEquals("Hello", persisted)
        assertTrue(viewModel.uiState.value.messages[1].content.startsWith("Hello"))
    }

    @Test
    fun stopPersistsPartialContent() = runChatTest(
        script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000)),
    ) { viewModel, _, _, store ->
        viewModel.send("Hi")
        advanceTimeBy(100)
        runCurrent()

        viewModel.stop()
        runCurrent()
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!
        val persisted = store.messages(conversationId).first()
        assertEquals("Hel", persisted.last { it.role == "ASSISTANT" }.content)
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
    }

    @Test
    fun failedEmptyAssistantPlaceholderIsNotPersisted() = runChatTest(
        script = listOf(ScriptedEvent.Delay(50), ScriptedEvent.Fail(FakeFailure)),
    ) { viewModel, _, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!
        val persisted = store.messages(conversationId).first()
        assertEquals(listOf("USER"), persisted.map { it.role })
        assertEquals(listOf("Hi"), persisted.map { it.content })
        assertTrue(viewModel.uiState.value.status is ChatStatus.Error)
    }

    @Test
    fun failedGenerationKeepsPartialAssistantContent() = runChatTest(
        script = listOf(ScriptedEvent.Delay(50), ScriptedEvent.Emit("Hel"), ScriptedEvent.Fail(FakeFailure)),
    ) { viewModel, _, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!
        val persisted = store.messages(conversationId).first()
        assertEquals(listOf("USER", "ASSISTANT"), persisted.map { it.role })
        assertEquals("Hel", persisted.last().content)
        assertTrue(viewModel.uiState.value.status is ChatStatus.Error)
    }

    @Test
    fun editAndResendPersistsTruncation() = runChatTest(
        script = listOf(ScriptedEvent.Emit("First reply")),
    ) { viewModel, provider, _, store ->
        viewModel.send("First")
        advanceUntilIdle()
        provider.script = listOf(ScriptedEvent.Emit("Second reply"))
        viewModel.send("Second")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!
        assertEquals(4, store.messages(conversationId).first().size)
        val firstUserId = viewModel.uiState.value.messages[0].id

        provider.script = listOf(ScriptedEvent.Emit("Edited reply"))
        viewModel.setDraft("Edited")
        viewModel.editAndResend(firstUserId, "Edited")
        advanceUntilIdle()

        val persisted = store.messages(conversationId).first()
        assertEquals(2, persisted.size)
        assertEquals(firstUserId, persisted[0].id)
        assertEquals("Edited", persisted[0].content)
        assertEquals("Edited reply", persisted[1].content)
    }

    @Test
    fun newRepositoryOverSameStoreRestoresConversation() = runChatTest(helloScript) { viewModel, provider, _, store ->
        viewModel.send("Hi")
        viewModel.setDraft("unsent draft")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!
        val persistedIds = store.messages(conversationId).first().map { it.id }

        val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
        val restoredRepository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = UnconfinedTestDispatcher(testScheduler),
            store = store,
            clock = { testScheduler.currentTime },
        )
        restoredRepository.setDraft("leftover draft")
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()

        val state = restoredViewModel.uiState.value
        assertEquals(conversationId, state.conversationId)
        assertEquals("", state.draft)
        assertEquals(persistedIds, state.messages.map { it.id })
        assertEquals(listOf(Role.USER to "Hi", Role.ASSISTANT to "Hello"), state.messages.map { it.role to it.content })
    }

    @Test
    fun anAnswerInterruptedBeforeAnyTokenIsNotRestoredAsAnEmptyMessage() =
        runChatTest(helloScript) { _, provider, _, store ->
            store.createConversation("interrupted", "Interrupted", now = 1)
            store.appendMessage(
                com.assistant.app.data.local.MessageEntity("m1", "interrupted", "USER", "question", 1),
            )
            store.appendMessage(
                com.assistant.app.data.local.MessageEntity("m2", "interrupted", "ASSISTANT", "", 2),
            )

            val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
            val restored = ChatRepository(
                chatLlm = chatLlm,
                generationDispatcher = UnconfinedTestDispatcher(testScheduler),
                store = store,
                clock = { testScheduler.currentTime },
            )
            restored.openConversation("interrupted")

            val state = restored.uiState.value
            assertEquals(ChatStatus.Idle, state.status)
            assertEquals(listOf(Role.USER to "question"), state.messages.map { it.role to it.content })
        }

    @Test
    fun openConversationDuringGenerationStopsAndResets() = runChatTest(
        script = listOf(ScriptedEvent.Delay(100), ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(100_000)),
    ) { viewModel, provider, repository, store ->
        store.createConversation("saved", "Saved conversation", now = 1)
        store.appendMessage(
            com.assistant.app.data.local.MessageEntity("sm1", "saved", "USER", "saved question", 1),
        )

        viewModel.send("Hi")
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ChatStatus.Generating, viewModel.uiState.value.status)
        viewModel.setDraft("in-flight draft")

        viewModel.openConversation("saved")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("saved", state.conversationId)
        assertEquals(ChatStatus.Idle, state.status)
        assertEquals("", state.draft)
        assertEquals(listOf(Role.USER to "saved question"), state.messages.map { it.role to it.content })

        val interruptedId = repository.conversations.first().first { it.id != "saved" }.id
        val interrupted = store.messages(interruptedId).first()
        assertEquals("Hel", interrupted.last { it.role == "ASSISTANT" }.content)

        provider.script = listOf(ScriptedEvent.Emit("Saved answer"))
        viewModel.send("Saved question")
        advanceUntilIdle()
        assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
        assertEquals("Saved answer", viewModel.uiState.value.messages.last().content)
    }

    @Test
    fun deleteConversationRemovesItFromHistory() = runChatTest(helloScript) { viewModel, _, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!

        viewModel.deleteConversation(conversationId)
        advanceUntilIdle()

        assertTrue(store.conversations().first().isEmpty())
        assertTrue(store.messages(conversationId).first().isEmpty())
    }

    @Test
    fun deletingOpenConversationResetsStateAndNextSendStartsFreshConversation() =
        runChatTest(helloScript) { viewModel, _, _, store ->
            viewModel.send("Hi")
            advanceUntilIdle()
            val deletedId = viewModel.uiState.value.conversationId!!

            viewModel.deleteConversation(deletedId)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.conversationId)
            assertEquals(emptyList<UiMessage>(), viewModel.uiState.value.messages)
            assertEquals(ChatStatus.Idle, viewModel.uiState.value.status)
            assertTrue(store.conversations().first().isEmpty())

            viewModel.send("Fresh")
            advanceUntilIdle()

            val newId = viewModel.uiState.value.conversationId!!
            assertTrue(newId != deletedId)
            val conversations = store.conversations().first()
            assertEquals(listOf(newId), conversations.map { it.id })
            assertEquals("Fresh", conversations.single().title)
            assertEquals(
                listOf("USER", "ASSISTANT"),
                store.messages(newId).first().map { it.role },
            )
            assertTrue(store.messages(deletedId).first().isEmpty())
        }

    @Test
    fun persistedMessagesNeverContainSecretMaterial() = runChatTest(helloScript) { viewModel, _, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()

        val conversationId = viewModel.uiState.value.conversationId!!
        val contents = store.messages(conversationId).first().map { it.content }
        assertEquals(listOf("Hi", "Hello"), contents)
        assertFalse(contents.any { it.contains(secretMarker) })
        val conversation = store.conversations().first().single()
        assertFalse(conversation.title.contains(secretMarker))
    }

    @Test
    fun versionsPersistAndRestoreIntoFreshRepository() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!
        val assistantId = viewModel.uiState.value.messages[1].id
        provider.script = listOf(ScriptedEvent.Emit("Hello again"))
        viewModel.regenerate()
        advanceUntilIdle()

        val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
        val restoredRepository = ChatRepository(
            chatLlm = chatLlm,
            generationDispatcher = UnconfinedTestDispatcher(testScheduler),
            store = store,
            clock = { testScheduler.currentTime },
        )
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()

        val restored = restoredViewModel.uiState.value.messages[1]
        assertEquals(assistantId, restored.id)
        assertEquals("Hello again", restored.content)
        assertEquals(listOf("Hello", "Hello again"), restored.versions)
        assertEquals(1, restored.selectedVersion)

        restoredViewModel.switchVersion(assistantId, 0)
        advanceUntilIdle()
        val persisted = store.messages(conversationId).first().last { it.role == "ASSISTANT" }
        assertEquals("Hello", persisted.content)
        assertEquals(0, persisted.selectedVersion)
    }

    @Test
    fun editAndResendRemovesVersionRowsOfDroppedMessages() = runChatTest(
        script = listOf(ScriptedEvent.Emit("First reply")),
    ) { viewModel, provider, _, store ->
        viewModel.send("First")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!
        provider.script = listOf(ScriptedEvent.Emit("Regenerated reply"))
        viewModel.regenerate()
        advanceUntilIdle()
        assertEquals(2, store.messageVersions(conversationId).first().size)

        provider.script = listOf(ScriptedEvent.Emit("Edited reply"))
        val firstUserId = viewModel.uiState.value.messages[0].id
        viewModel.editAndResend(firstUserId, "Edited")
        advanceUntilIdle()

        assertEquals(1, store.messageVersions(conversationId).first().size)
        val persisted = store.messages(conversationId).first().last { it.role == "ASSISTANT" }
        assertEquals("Edited reply", persisted.content)
        val versionRow = store.messageVersions(conversationId).first().single()
        assertEquals("Edited reply", versionRow.content)
        assertEquals(persisted.id, versionRow.messageId)
    }

    @Test
    fun attachmentsPersistRideRequestsAndCleanUp() {
        val dir = java.nio.file.Files.createTempDirectory("attach").toFile()
        runChatTest(
            script = listOf(ScriptedEvent.Emit("Hello")),
            attachmentsDir = dir,
        ) { viewModel, provider, repository, store ->
            val image = File(dir, "img.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val text = File(dir, "notes.txt").apply { writeText("file body") }
            repository.addPendingAttachments(
                listOf(
                    com.assistant.app.llm.model.UiAttachment(
                        id = "img1",
                        kind = com.assistant.app.llm.model.UiAttachment.Kind.IMAGE,
                        displayName = "img.jpg",
                        mime = "image/jpeg",
                        path = image.absolutePath,
                        sizeBytes = 3,
                    ),
                    com.assistant.app.llm.model.UiAttachment(
                        id = "txt1",
                        kind = com.assistant.app.llm.model.UiAttachment.Kind.TEXT,
                        displayName = "notes.txt",
                        mime = "text/plain",
                        path = text.absolutePath,
                        sizeBytes = 9,
                    ),
                ),
            )

            viewModel.send("What is this?")
            advanceUntilIdle()
            val conversationId = viewModel.uiState.value.conversationId!!

            val request = provider.requests.single()
            assertEquals(1, request.images.size)
            assertTrue(request.messages.last().second.contains("What is this?"))
            assertTrue(request.messages.last().second.contains("[File: notes.txt]"))
            assertTrue(request.messages.last().second.contains("file body"))
            assertTrue(viewModel.uiState.value.pendingAttachments.isEmpty())

            assertEquals(2, store.attachments(conversationId).first().size)
            val restoredChatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
            val restoredRepository = ChatRepository(
                chatLlm = restoredChatLlm,
                generationDispatcher = UnconfinedTestDispatcher(testScheduler),
                store = store,
                clock = { testScheduler.currentTime },
                attachmentsDir = dir,
            )
            val restoredViewModel = ChatViewModel(restoredRepository, restoredChatLlm)
            restoredViewModel.openConversation(conversationId)
            advanceUntilIdle()
            assertEquals(2, restoredViewModel.uiState.value.messages[0].attachments.size)

            assertTrue(image.exists())
            viewModel.deleteConversation(conversationId)
            advanceUntilIdle()
            assertEquals(0, store.attachments(conversationId).first().size)
            assertFalse(image.exists())
            assertFalse(text.exists())
        }
    }

    @Test
    fun orphanAttachmentsSweptWhenUnreferencedInDatabase() = runTest {
        val ingester = AttachmentIngester(ApplicationProvider.getApplicationContext())
        ingester.attachmentsDir.mkdirs()
        val orphanFile = File(ingester.attachmentsDir, "orphan.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val validFile = File(ingester.attachmentsDir, "valid.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }

        assertTrue(orphanFile.exists())
        assertTrue(validFile.exists())

        ingester.sweepOrphans { setOf(validFile.absolutePath) }

        assertFalse(orphanFile.exists())
        assertTrue(validFile.exists())

        validFile.delete()
    }

    @Test
    fun reasoningPersistsAndRestores() = runChatTest(
        script = listOf(ScriptedEvent.Reasoning("thinking"), ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!

        val persisted = store.messages(conversationId).first().last { it.role == "ASSISTANT" }
        assertEquals("thinking", persisted.reasoning)

        val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
        val restoredRepository = ChatRepository(chatLlm, UnconfinedTestDispatcher(testScheduler), store)
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()
        assertEquals("thinking", restoredViewModel.uiState.value.messages[1].reasoning)
    }

    @Test
    fun sourcesPersistAndRestore() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _, store ->
        val repository = ChatRepository(
            chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL)),
            generationDispatcher = UnconfinedTestDispatcher(testScheduler),
            store = store,
            clock = { testScheduler.currentTime },
            webSearch = { _ ->
                com.assistant.app.llm.model.SearchOutcome.Success(
                    listOf(
                        com.assistant.app.llm.model.SearchResult("Result", "https://example.com/a", "snip"),
                    ),
                )
            },
        )
        val searchViewModel = ChatViewModel(repository, MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL)))
        repository.setSearchEnabled(true)
        searchViewModel.send("Hi")
        advanceUntilIdle()
        val conversationId = searchViewModel.uiState.value.conversationId!!

        val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
        val restoredRepository = ChatRepository(chatLlm, UnconfinedTestDispatcher(testScheduler), store)
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()
        assertEquals(
            listOf("https://example.com/a"),
            restoredViewModel.uiState.value.messages[1].sources.map { it.url },
        )
    }

    @Test
    fun switchVersionPreservesUnversionedContent() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _, store ->
        viewModel.send("Hi")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!
        val assistantId = viewModel.uiState.value.messages[1].id

        store.updateMessageContent(assistantId, "partial answer", reasoning = "", updatedAt = 5)
        provider.script = listOf(ScriptedEvent.Emit("Fresh"))

        val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
        val restoredRepository = ChatRepository(chatLlm, UnconfinedTestDispatcher(testScheduler), store)
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()

        restoredViewModel.switchVersion(assistantId, 0)
        advanceUntilIdle()
        assertEquals("Hello", restoredViewModel.uiState.value.messages[1].content)

        val versions = restoredViewModel.uiState.value.messages[1].versions
        assertEquals(listOf("Hello", "partial answer"), versions)
        restoredViewModel.switchVersion(assistantId, 1)
        advanceUntilIdle()
        assertEquals("partial answer", restoredViewModel.uiState.value.messages[1].content)
    }

    @Test
    fun restoredSourcesReArmWebContext() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, _, store ->
        val repository = ChatRepository(
            chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL)),
            generationDispatcher = UnconfinedTestDispatcher(testScheduler),
            store = store,
            clock = { testScheduler.currentTime },
            webSearch = { _ ->
                com.assistant.app.llm.model.SearchOutcome.Success(
                    listOf(
                        com.assistant.app.llm.model.SearchResult("R", "https://example.com/x", "snip"),
                    ),
                )
            },
        )
        val searchViewModel = ChatViewModel(repository, MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL)))
        repository.setSearchEnabled(true)
        searchViewModel.send("Hi")
        advanceUntilIdle()
        val conversationId = searchViewModel.uiState.value.conversationId!!

        val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
        val restoredRepository = ChatRepository(chatLlm, UnconfinedTestDispatcher(testScheduler), store)
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()
        assertEquals(
            listOf("https://example.com/x"),
            restoredViewModel.uiState.value.messages[0].webResults.map { it.url },
        )
    }

    @Test
    fun searchTogglePersistsAndRestores() = runChatTest(
        script = listOf(ScriptedEvent.Emit("Hello")),
    ) { viewModel, provider, repository, store ->
        repository.setSearchEnabled(true)
        viewModel.send("Hi")
        advanceUntilIdle()
        val conversationId = viewModel.uiState.value.conversationId!!

        val chatLlm = MutableStateFlow(ChatLlmState.Ready(provider, SECRET_MARKER_MODEL))
        val restoredRepository = ChatRepository(chatLlm, UnconfinedTestDispatcher(testScheduler), store)
        val restoredViewModel = ChatViewModel(restoredRepository, chatLlm)
        restoredViewModel.openConversation(conversationId)
        advanceUntilIdle()
        assertTrue(restoredViewModel.uiState.value.searchEnabled)
    }

    private companion object {
        const val SECRET_MARKER_MODEL = "test-model"
        val FakeFailure = com.assistant.app.llm.model.ProviderError.Unknown
    }
}
