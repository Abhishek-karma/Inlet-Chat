package com.assistant.app.voice

import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ChatStatus
import com.assistant.app.data.VoiceStatus
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.ui.chat.ChatViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Before
import org.junit.Test

/** Scriptable [VoiceInput.Engine] fake: the test drives the events directly. */
private class FakeVoiceInputEngine(var available: Boolean = true) : VoiceInput.Engine {
    var started = 0
    var stopped = 0
    var onEvent: ((VoiceInputEvent) -> Unit)? = null

    override fun isAvailable(): Boolean = available
    override fun start(onEvent: (VoiceInputEvent) -> Unit) {
        started++
        this.onEvent = onEvent
    }

    override fun stop() {
        stopped++
        onEvent = null
    }

    fun emit(event: VoiceInputEvent) {
        onEvent?.invoke(event)
    }
}

/** Scriptable [VoiceOutput.Engine] fake: holds the completion for the test. */
private class FakeVoiceOutputEngine(private val available: Boolean = true) : VoiceOutput.Engine {
    val spoken = mutableListOf<String>()
    val rates = mutableListOf<Float>()
    val voiceIds = mutableListOf<String?>()
    var voices: List<VoiceOption> = emptyList()
    var pendingDone: (() -> Unit)? = null
    var stopped = 0
    /** True when stop() discarded an utterance that had not finished. */
    var stoppedWhileSpeaking = false
    private set

    override fun isAvailable(): Boolean = available
    override fun voices(onReady: (List<VoiceOption>) -> Unit) = onReady(voices)
    override fun speak(text: String, speechRate: Float, voiceId: String?, onDone: () -> Unit) {
        spoken += text
        rates += speechRate
        voiceIds += voiceId
        pendingDone = onDone
    }

    override fun stop() {
        stopped++
        stoppedWhileSpeaking = stoppedWhileSpeaking || pendingDone != null
        pendingDone = null
    }

    fun finish() {
        val done = pendingDone
        pendingDone = null
        done?.invoke()
    }
}

/**
 * Behavior contract for the voice layer over the chat pipeline: recognition
 * lands in the draft (never auto-sends), failures degrade to text, and completed
 * assistant messages are spoken only when voice output is enabled.
 * The platform recognizers themselves are device-only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChatTest {

    private lateinit var inputEngine: FakeVoiceInputEngine
    private lateinit var outputEngine: FakeVoiceOutputEngine

    @Before
    fun setUp() {
        inputEngine = FakeVoiceInputEngine()
        outputEngine = FakeVoiceOutputEngine()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun runVoiceTest(
        voiceOutputEnabled: Boolean = true,
        voiceAutoPlay: Boolean = true,
        voiceSpeed: Float = 1.0f,
        voiceId: String? = null,
        block: suspend TestScope.(ChatViewModel, FakeVoiceInputEngine, FakeVoiceOutputEngine) -> Unit,
    ) = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(
            listOf(ScriptedEvent.Emit("Hello there"), ScriptedEvent.Emit(" again")),
        )
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val viewModel = ChatViewModel(
            repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher),
            chatLlm = chatLlm,
            voiceInput = VoiceInput(inputEngine),
            voiceOutput = VoiceOutput(outputEngine),
            isVoiceOutputEnabled = { voiceOutputEnabled },
            voiceAutoPlay = { voiceAutoPlay },
            voiceSpeed = { voiceSpeed },
            voiceId = { voiceId },
        )
        block(viewModel, inputEngine, outputEngine)
    }

    @Test
    fun `transcript lands in draft without auto-send`() = runVoiceTest { viewModel, input, _ ->
        viewModel.onMicClick()
        assertEquals(VoiceStatus.Listening, viewModel.uiState.value.voiceStatus)
        assertEquals(1, input.started)

        input.emit(VoiceInputEvent.Transcribing)
        assertEquals(VoiceStatus.Transcribing, viewModel.uiState.value.voiceStatus)

        input.emit(VoiceInputEvent.Transcript("hello world"))
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertEquals("hello world", viewModel.uiState.value.draft)
        assertEquals(0, viewModel.uiState.value.messages.size)
    }

    /** Triggers [ViewModel.onCleared] the way the framework does, via a store. */
    private fun ChatViewModel.clearViaStoreForTest() {
        val store = ViewModelStore()
        store.put("test", this)
        store.clear()
    }

    @Test
    fun `transcript appends to existing draft`() = runVoiceTest { viewModel, input, _ ->
        viewModel.setDraft("Note:")
        viewModel.onMicClick()
        input.emit(VoiceInputEvent.Transcript(" buy milk"))

        assertEquals("Note: buy milk", viewModel.uiState.value.draft)
    }

    @Test
    fun `no match shows hint and keeps draft`() = runVoiceTest { viewModel, input, _ ->
        viewModel.setDraft("unchanged")
        viewModel.onMicClick()
        input.emit(VoiceInputEvent.Failed(VoiceInputError.NoMatch))

        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertEquals("unchanged", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.voiceHint)

        viewModel.dismissVoiceHint()
        assertFalse(viewModel.uiState.value.voiceHint)
    }

    @Test
    fun `mic failure degrades to text without hint`() = runVoiceTest { viewModel, input, _ ->
        viewModel.onMicClick()
        input.emit(VoiceInputEvent.Failed(VoiceInputError.Busy))

        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertFalse(viewModel.uiState.value.voiceHint)
    }

    @Test
    fun `second mic tap cancels listening`() = runVoiceTest { viewModel, input, _ ->
        viewModel.onMicClick()
        viewModel.onMicClick()
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertEquals(1, input.started)
        assertEquals(1, input.stopped)

        input.emit(VoiceInputEvent.Transcript("stale"))
        assertEquals("", viewModel.uiState.value.draft)
    }

    @Test
    fun `completed response is spoken when enabled`() = runVoiceTest { viewModel, _, output ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(VoiceStatus.Speaking, viewModel.uiState.value.voiceStatus)
        assertEquals(listOf("Hello there again"), output.spoken)

        output.finish()
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
    }

    @Test
    fun `clearing the viewmodel stops speech`() = runVoiceTest { viewModel, _, output ->
        viewModel.onMicClick()
        viewModel.send("Hi")
        advanceUntilIdle()
        assertEquals(VoiceStatus.Speaking, viewModel.uiState.value.voiceStatus)

        viewModel.clearViaStoreForTest()

        assertEquals(1, output.stopped)
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
    }
    @Test
    fun `completed response is not spoken when disabled`() = runVoiceTest(
        voiceOutputEnabled = false,
    ) { viewModel, _, output ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertTrue(output.spoken.isEmpty())
        assertNull(output.pendingDone)
    }

    @Test
    fun `completed response is not spoken when auto-play is off`() = runVoiceTest(
        voiceAutoPlay = false,
    ) { viewModel, _, output ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertTrue(output.spoken.isEmpty())
    }

    @Test
    fun `speakMessage speaks on demand when auto-play is off`() = runVoiceTest(
        voiceAutoPlay = false,
    ) { viewModel, _, output ->
        viewModel.send("Hi")
        advanceUntilIdle()
        assertTrue(output.spoken.isEmpty())

        val messageId = viewModel.uiState.value.messages.last().id
        viewModel.speakMessage(messageId)
        assertEquals(VoiceStatus.Speaking, viewModel.uiState.value.voiceStatus)
        assertEquals(listOf("Hello there again"), output.spoken)

        output.finish()
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)

        viewModel.speakMessage("no-such-message")
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertEquals(1, output.spoken.size)
    }

    @Test
    fun `completed response is spoken as stripped plain text`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(
            listOf(
                ScriptedEvent.Emit("## Summary\n"),
                ScriptedEvent.Emit("See [docs](https://example.com) and `code`.\n"),
                ScriptedEvent.Emit("```py\nprint('x')\n```\n"),
                ScriptedEvent.Emit("**Done** - item"),
            ),
        )
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val viewModel = ChatViewModel(
            repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher),
            chatLlm = chatLlm,
            voiceInput = VoiceInput(inputEngine),
            voiceOutput = VoiceOutput(outputEngine),
            isVoiceOutputEnabled = { true },
        )
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(listOf("Summary See docs and code. Done - item"), outputEngine.spoken)
    }

    @Test
    fun `speech rate is passed to the engine`() = runVoiceTest(voiceSpeed = 1.5f) { viewModel, _, output ->
        viewModel.send("Hi")
        advanceUntilIdle()

        assertEquals(listOf(1.5f), output.rates)
    }

    @Test
    fun `selected voice id is passed to the engine`() =
        runVoiceTest(voiceId = "com.google.android.tts:en-us-natural:en-US") { viewModel, _, output ->
            viewModel.send("Hi")
            advanceUntilIdle()

            assertEquals(listOf("com.google.android.tts:en-us-natural:en-US"), output.voiceIds)
        }

    @Test
    fun `no voice id reaches the engine when the default is selected`() =
        runVoiceTest(voiceId = null) { viewModel, _, output ->
            viewModel.send("Hi")
            advanceUntilIdle()

            assertEquals(listOf<String?>(null), output.voiceIds)
        }

    @Test
    fun `send while speaking cancels tts`() = runVoiceTest { viewModel, _, output ->
        viewModel.send("First")
        advanceUntilIdle()
        assertEquals(VoiceStatus.Speaking, viewModel.uiState.value.voiceStatus)

        viewModel.send("Second")
        advanceUntilIdle()

        assertEquals(1, output.stopped)
        assertEquals(listOf("Hello there again", "Hello there again"), output.spoken)

        output.finish()
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
    }

    @Test
    fun `stopped generation is not spoken`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(
            listOf(ScriptedEvent.Delay(1_000), ScriptedEvent.Emit("Hello")),
        )
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val viewModel = ChatViewModel(
            repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher),
            chatLlm = chatLlm,
            voiceInput = VoiceInput(inputEngine),
            voiceOutput = VoiceOutput(outputEngine),
        )
        viewModel.send("Hi")
        runCurrent()
        viewModel.stop()
        advanceUntilIdle()

        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertTrue(outputEngine.spoken.isEmpty())
    }

    @Test
    fun `mic tap without recognition support is a no-op`() = runVoiceTest { viewModel, input, _ ->
        input.available = false
        viewModel.onMicClick()
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertEquals(0, input.started)
    }

    @Test
    fun `new conversation mid-generation does not speak`() = runTest {
        val viewModel = midGenerationViewModel(UnconfinedTestDispatcher(testScheduler))
        viewModel.send("Hi")
        advanceTimeBy(500)
        runCurrent()

        viewModel.newConversation()
        advanceUntilIdle()

        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertTrue(outputEngine.spoken.isEmpty())
    }

    @Test
    fun `opening a conversation mid-generation does not speak`() = runTest {
        val viewModel = midGenerationViewModel(UnconfinedTestDispatcher(testScheduler))
        viewModel.send("First")
        advanceUntilIdle()
        outputEngine.finish()
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertEquals(1, outputEngine.spoken.size)
        val firstConversationId = viewModel.uiState.value.conversationId!!

        viewModel.newConversation()
        outputEngine.spoken.clear()
        viewModel.send("Second")
        advanceTimeBy(500)
        runCurrent()

        viewModel.openConversation(firstConversationId)
        advanceUntilIdle()

        assertEquals(firstConversationId, viewModel.uiState.value.conversationId)
        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertTrue(outputEngine.spoken.isEmpty())
    }

    @Test
    fun `deleting the open conversation mid-generation does not speak`() = runTest {
        val viewModel = midGenerationViewModel(UnconfinedTestDispatcher(testScheduler))
        viewModel.send("Hi")
        advanceTimeBy(500)
        runCurrent()

        viewModel.deleteConversation(viewModel.uiState.value.conversationId!!)
        advanceUntilIdle()

        assertEquals(VoiceStatus.Idle, viewModel.uiState.value.voiceStatus)
        assertTrue(outputEngine.spoken.isEmpty())
    }

    @Test
    fun `deleting another conversation mid-generation still speaks the open answer`() = runTest {
        val viewModel = midGenerationViewModel(UnconfinedTestDispatcher(testScheduler))
        viewModel.send("Hi")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(ChatStatus.Generating, viewModel.uiState.value.status)

        viewModel.deleteConversation("some-other-conversation")
        advanceUntilIdle()

        assertEquals(VoiceStatus.Speaking, viewModel.uiState.value.voiceStatus)
        // The scripted answer completes and is spoken as one utterance.
        assertEquals(listOf("Hello there again"), outputEngine.spoken)
    }

    /**
     * A voice-enabled ViewModel whose provider streams nothing until well
     * after the test's switch point, so the generation is reliably mid-flight.
     */
    private fun midGenerationViewModel(mainDispatcher: CoroutineDispatcher): ChatViewModel {
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(
            listOf(ScriptedEvent.Emit("Hello there"), ScriptedEvent.Delay(1_000), ScriptedEvent.Emit(" again")),
        )
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        return ChatViewModel(
            repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher),
            chatLlm = chatLlm,
            voiceInput = VoiceInput(inputEngine),
            voiceOutput = VoiceOutput(outputEngine),
            isVoiceOutputEnabled = { true },
        )
    }

    @Test
    fun `mic failure with unavailable hardware sets Error state and can be retried`() = runVoiceTest { viewModel, input, _ ->
        viewModel.onMicClick()
        assertEquals(VoiceStatus.Listening, viewModel.uiState.value.voiceStatus)

        input.emit(VoiceInputEvent.Failed(VoiceInputError.MicUnavailable))
        assertEquals(VoiceStatus.Error, viewModel.uiState.value.voiceStatus)

        // Tapping mic while in Error state resets it and starts listening again
        viewModel.onMicClick()
        assertEquals(VoiceStatus.Listening, viewModel.uiState.value.voiceStatus)
    }

    @Test
    fun `unavailable voice output transitions to Error state instead of hanging`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val provider = FakeLlmProvider(listOf(ScriptedEvent.Emit("Hello")))
        val chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Ready(provider, "test-model"))
        val unavailVm = ChatViewModel(
            repository = ChatRepository(chatLlm, generationDispatcher = mainDispatcher),
            chatLlm = chatLlm,
            voiceOutput = VoiceOutput.unavailable(),
            isVoiceOutputEnabled = { true },
            voiceAutoPlay = { true },
        )
        unavailVm.send("Hi")
        advanceUntilIdle()
        assertEquals(VoiceStatus.Error, unavailVm.uiState.value.voiceStatus)
    }
}

