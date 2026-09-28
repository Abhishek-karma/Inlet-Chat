package com.assistant.app.data

import com.assistant.app.data.local.AttachmentEntity
import com.assistant.app.data.local.ConversationEntity
import com.assistant.app.data.local.MessageEntity
import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.model.Role
import com.assistant.app.llm.model.SearchError
import com.assistant.app.llm.model.SearchOutcome
import com.assistant.app.llm.model.SearchResult
import com.assistant.app.llm.model.UiAttachment
import com.assistant.app.llm.model.UiMessage
import com.assistant.app.llm.model.searchResultsFromJson
import com.assistant.app.llm.model.toSearchJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import android.util.Base64
import java.io.File
import java.util.UUID

sealed interface ChatStatus {
    data object Idle : ChatStatus
    data object Generating : ChatStatus
    data class Error(val message: String) : ChatStatus
}

/** Voice state, layered on the text pipeline instead of a second chat flow. */
enum class VoiceStatus { Idle, Listening, Transcribing, Speaking }

data class ChatUiState(
    val conversationId: String? = null,
    val messages: List<UiMessage> = emptyList(),
    val status: ChatStatus = ChatStatus.Idle,
    val draft: String = "",
    val needsSetup: Boolean = false,
    val voiceStatus: VoiceStatus = VoiceStatus.Idle,
    /** One-shot "didn't catch that" hint above the composer. */
    val voiceHint: Boolean = false,
    /** Files staged in the composer for the next message. */
    val pendingAttachments: List<UiAttachment> = emptyList(),
    /** One-shot attachment ingest/limit error above the composer. */
    val attachmentError: String? = null,
    /** Web search for the current conversation. */
    val searchEnabled: Boolean = false,
    /** One-shot web-search notice above the composer. */
    val searchNotice: String? = null,
)

/**
 * The generation wiring resolved from the active provider.
 *
 * [NeedsSetup] means there is no usable provider, so sends are refused and the
 * setup prompt shows. [Ready] is rebuilt whenever the configuration changes.
 */
sealed interface ChatLlmState {
    data object Loading : ChatLlmState
    data class Ready(
        val provider: LlmProvider,
        val model: String,
        val providerId: Long = 0,
        val name: String = "",
    ) : ChatLlmState
    data object NeedsSetup : ChatLlmState
}

/**
 * Owns the conversation: the message list, the in-flight generation job, and
 * every call into the configured [LlmProvider].
 *
 * Mutating intents are `suspend` so clearing the ViewModel cancels a stream.
 * A failed generation keeps any partial answer as a normal message; [retry]
 * removes it and re-requests.
 */
class ChatRepository(
    private val chatLlm: StateFlow<ChatLlmState>,
    private val generationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val store: ConversationStore? = null,
    private val followUpSuggestions: (suspend (LlmProvider, String, String, String) -> List<String>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    internal val attachmentsDir: File? = null,
    private val webSearch: (suspend (String) -> SearchOutcome?)? = null,
) {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null

    /** The failed generation's message id while it is still shown with an error. */
    private var failedAssistantId: String? = null

    private var lastPersistAt: Long = 0L

    /** Working copy of the loaded conversation's answer versions, keyed by message id. */
    private val versionCache = AnswerVersions()

    /** Conversation snapshots for the no-persistence (test) mode. */
    private val conversationSnapshots = LinkedHashMap<String, List<UiMessage>>()

    /** Saved conversations for the history screen, newest activity first. */
    val conversations: Flow<List<ConversationEntity>> = store?.conversations() ?: emptyFlow()

    fun setDraft(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    /**
     * Reflects provider availability into the UI state. [ChatLlmState.Loading]
     * never reaches here, so the first screen does not flash the setup prompt.
     */
    fun setNeedsSetup(needsSetup: Boolean) {
        _uiState.update { it.copy(needsSetup = needsSetup) }
    }

    fun setVoiceStatus(status: VoiceStatus) {
        _uiState.update { it.copy(voiceStatus = status) }
    }

    fun setVoiceHint(show: Boolean) {
        _uiState.update { it.copy(voiceHint = show) }
    }

    /** Appends a recognized transcript to the draft; never auto-sends. */
    fun applyVoiceTranscript(text: String) {
        val transcript = text.trim()
        if (transcript.isEmpty()) return
        _uiState.update { state ->
            val separator = when {
                state.draft.isBlank() -> ""
                state.draft.endsWith(" ") || state.draft.endsWith("\n") -> ""
                else -> " "
            }
            state.copy(draft = state.draft + separator + transcript)
        }
    }

    /**
     * Stages ingested attachments. Over-limit images or text files set
     * [ChatUiState.attachmentError] and stage nothing.
     */
    fun addPendingAttachments(attachments: List<UiAttachment>) {
        if (attachments.isEmpty()) return
        _uiState.update { state ->
            val images = state.pendingAttachments.count { it.kind == UiAttachment.Kind.IMAGE } +
                attachments.count { it.kind == UiAttachment.Kind.IMAGE }
            val texts = state.pendingAttachments.count { it.kind == UiAttachment.Kind.TEXT } +
                attachments.count { it.kind == UiAttachment.Kind.TEXT }
            when {
                images > MAX_IMAGES_PER_MESSAGE -> state.copy(attachmentError = "Up to $MAX_IMAGES_PER_MESSAGE images per message.")
                texts > MAX_TEXTS_PER_MESSAGE -> state.copy(attachmentError = "Up to $MAX_TEXTS_PER_MESSAGE text files per message.")
                else -> state.copy(
                    pendingAttachments = state.pendingAttachments + attachments,
                    attachmentError = null,
                )
            }
        }
    }

    /** Removes one staged attachment and deletes its stored copy. */
    fun removePendingAttachment(id: String) {
        var removedPath: String? = null
        _uiState.update { state ->
            val removed = state.pendingAttachments.firstOrNull { it.id == id } ?: return@update state
            removedPath = removed.path
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == id })
        }
        removedPath?.let(::deleteFile)
    }

    fun clearAttachmentError() {
        _uiState.update { it.copy(attachmentError = null) }
    }

    /** Shows a one-shot attachment error (ingest failure) above the composer. */
    fun setAttachmentError(message: String) {
        _uiState.update { it.copy(attachmentError = message) }
    }

    private fun clearPendingAttachments() {
        _uiState.update { it.copy(pendingAttachments = emptyList()) }
    }

    /**
     * Drops staged attachments and their stored copies: they belong to the
     * conversation being left and must never be sent into another one.
     */
    private fun discardStagedAttachments() {
        val staged = _uiState.value.pendingAttachments
        if (staged.isEmpty()) return
        _uiState.update { it.copy(pendingAttachments = emptyList(), attachmentError = null) }
        staged.forEach { deleteFile(it.path) }
    }

    /** The plain-text transcript of one saved conversation, or null. */
    suspend fun shareConversationText(id: String): String? {
        val s = store ?: return null
        val title = s.conversationTitle(id) ?: return null
        val messages = s.messages(id).first()
            .map { UiMessage(it.id, Role.valueOf(it.role), it.content, it.createdAt) }
        if (messages.none { it.content.isNotBlank() }) return null
        return buildShareText(title, messages)
    }

    /**
     * Toggles web search and persists it. Before the first send there is no
     * conversation row yet, so the state alone carries the toggle.
     */
    suspend fun setSearchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(searchEnabled = enabled, searchNotice = null) }
        store?.let { s ->
            _uiState.value.conversationId?.let { s.setSearchEnabled(it, enabled) }
        }
    }

    private fun setSearchNotice(message: String) {
        _uiState.update { it.copy(searchNotice = message) }
    }

    /** Clears the search notice (e.g. when the user edits the draft). */
    fun dismissSearchNotice() {
        _uiState.update { it.copy(searchNotice = null) }
    }

    /**
     * One search request for the user's message. A failure or empty result
     * leaves the answer to proceed without search, with a visible notice.
     */
    private suspend fun runWebSearch(query: String): List<SearchResult> {
        val search = webSearch ?: run {
            setSearchNotice(SEARCH_NOT_CONFIGURED)
            return emptyList()
        }
        // A throwing search service is an outcome, not a crash.
        val outcome = try {
            search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setSearchNotice(SearchError.Unknown.userMessage)
            return emptyList()
        }
        return when (outcome) {
            null -> {
                setSearchNotice(SEARCH_NOT_CONFIGURED)
                emptyList()
            }
            is SearchOutcome.Success ->
                if (outcome.results.isEmpty()) {
                    setSearchNotice(SearchError.NoResults.userMessage)
                    emptyList()
                } else {
                    outcome.results
                }
            is SearchOutcome.Failure -> {
                setSearchNotice(outcome.error.userMessage)
                emptyList()
            }
        }
    }

    private fun deleteFile(path: String) {
        if (attachmentsDir != null) {
            runCatching { File(path).delete() }
        }
    }

    fun stop() {
        generationJob?.cancel()
    }

    suspend fun send(text: String) {
        val content = text.trim()
        // An attachment-only send (blank text) is allowed.
        if ((content.isEmpty() && _uiState.value.pendingAttachments.isEmpty()) || isGenerating()) return
        // Reserve the generation slot before any suspension (search, store
        // writes): a second send during that window must not start a parallel
        // generation, and stop() must reach the in-flight preparation.
        val job = currentCoroutineContext().job
        generationJob = job
        _uiState.update { it.copy(status = ChatStatus.Generating) }
        try {
            sendPrepared(text, content, job)
        } catch (e: CancellationException) {
            // Stopped during search/preparation: settle what startGeneration
            // would have settled.
            _uiState.update { state ->
                if (generationJob === job && state.status is ChatStatus.Generating) {
                    state.copy(status = ChatStatus.Idle)
                } else {
                    state
                }
            }
            throw e
        }
    }

    private suspend fun sendPrepared(text: String, content: String, job: Job) {
        val llm = currentLlm() ?: run {
            _uiState.update { it.copy(status = ChatStatus.Idle) }
            refuseWithoutLlm()
            if (generationJob === job) generationJob = null
            return
        }
        failedAssistantId = null
        val isNewConversation = _uiState.value.conversationId == null
        val conversationId = _uiState.value.conversationId ?: newId()
        val attachments = _uiState.value.pendingAttachments
        val webResults = if (_uiState.value.searchEnabled && content.isNotEmpty()) {
            runWebSearch(content)
        } else {
            emptyList()
        }
        val userMessage = UiMessage(
            newId(),
            Role.USER,
            content,
            now(),
            attachments = attachments,
            webResults = webResults,
        )
        val assistantId = newId()
        _uiState.update { state ->
            state.copy(
                conversationId = conversationId,
                messages = state.messages +
                    userMessage +
                    UiMessage(assistantId, Role.ASSISTANT, "", now(), sources = webResults),
                draft = "",
            )
        }
        store?.let { s ->
            if (isNewConversation) {
                s.createConversation(conversationId, s.titleFor(content), userMessage.createdAt)
                if (_uiState.value.searchEnabled) {
                    s.setSearchEnabled(conversationId, true)
                }
            }
            s.appendMessage(userMessage.toEntity(conversationId))
            s.appendMessage(UiMessage(assistantId, Role.ASSISTANT, "", now()).toEntity(conversationId))
            attachments.forEach { s.appendAttachment(it.toEntity(userMessage.id, conversationId, now())) }
            if (webResults.isNotEmpty()) {
                s.updateSources(assistantId, webResults.toSearchJson())
            }
        }
        clearPendingAttachments()
        startGeneration(llm, assistantId)
    }

    suspend fun retry() {
        if (_uiState.value.status !is ChatStatus.Error || isGenerating()) return
        // Checked before touching the conversation so an unconfigured provider
        // leaves the message list and error status untouched.
        if (currentLlm() == null) {
            refuseWithoutLlm()
            return
        }
        val failedId = failedAssistantId
        failedAssistantId = null
        generationJob = currentCoroutineContext().job
        if (failedId != null) {
            _uiState.update { state ->
                state.copy(messages = state.messages.filterNot { it.id == failedId })
            }
            versionCache.remove(failedId)
            store?.let { s ->
                _uiState.value.conversationId?.let { conversationId ->
                    deleteFiles(s.deleteMessagesFrom(failedId, conversationId))
                }
            }
        }
        if (_uiState.value.messages.none { it.role == Role.USER }) return
        appendAssistantPlaceholderAndGenerate()
    }

    /** Clears an error banner without retrying; partial assistant content stays. */
    fun dismissError() {
        if (_uiState.value.status is ChatStatus.Error) {
            _uiState.update { it.copy(status = ChatStatus.Idle) }
        }
    }

    /**
     * Regenerates the last assistant response, keeping the old answer as a
     * version: the content is snapshotted, cleared, and the new answer streams
     * into the same message.
     */
    suspend fun regenerate() {
        val state = _uiState.value
        val last = state.messages.lastOrNull()
        if (state.status !is ChatStatus.Idle || isGenerating() || last?.role != Role.ASSISTANT) return
        // Checked before touching the conversation so an unconfigured provider
        // leaves the message list untouched.
        val llm = currentLlm() ?: run {
            refuseWithoutLlm()
            return
        }
        generationJob = currentCoroutineContext().job
        snapshotVersion(last.id)
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == last.id) m.copy(content = "", reasoning = "") else m
            })
        }
        store?.let { s -> state.conversationId?.let { s.updateMessageContent(last.id, "", "", now()) } }
        startGeneration(llm, last.id)
    }

    /**
     * Shows answer [index] of an assistant message. The selected answer becomes
     * the message content, so the conversation continues from it; the replaced
     * answer's follow-up suggestions are dropped.
     */
    suspend fun switchVersion(messageId: String, index: Int) {
        if (_uiState.value.status !is ChatStatus.Idle || isGenerating()) return
        val versions = versionCache.versionsOf(messageId) ?: return
        if (index !in versions.indices) return
        // Content restored after a process death mid-stream may not have a
        // version row yet; snapshot it so switching cannot lose the only copy.
        snapshotVersion(messageId)
        val current = versionCache.versionsOf(messageId).orEmpty()
        if (index !in current.indices) return
        _uiState.update { state ->
            state.copy(messages = state.messages.map { m ->
                if (m.id == messageId && m.role == Role.ASSISTANT) {
                    m.copy(content = current[index], selectedVersion = index, reasoning = "")
                } else {
                    m
                }
            })
        }
        store?.let { s ->
            s.updateMessageContent(messageId, current[index], "", now())
            s.updateSelectedVersion(messageId, index)
        }
    }

    /** Pins or unpins a saved conversation in the history list. */
    suspend fun setConversationPinned(id: String, pinned: Boolean) {
        store?.setPinned(id, pinned)
    }

    /** Renames a saved conversation; blank titles are ignored. */
    suspend fun renameConversation(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        store?.renameConversation(id, trimmed)
    }

    /**
     * Truncates the conversation at the given user message (everything after it
     * is removed), replaces its content, and re-requests from there.
     */
    suspend fun editAndResend(messageId: String, newContent: String) {
        val content = newContent.trim()
        if (content.isEmpty() || isGenerating()) return
        // Checked before truncating so an unconfigured provider leaves the
        // conversation untouched.
        if (currentLlm() == null) {
            refuseWithoutLlm()
            return
        }
        generationJob = currentCoroutineContext().job
        val current = _uiState.value
        val index = current.messages.indexOfFirst { it.id == messageId && it.role == Role.USER }
        if (index < 0) return
        failedAssistantId = null
        val edited = current.messages[index].copy(content = content)
        // The edited content came from the composer, so the draft is consumed.
        _uiState.update { it.copy(messages = it.messages.take(index) + edited, draft = "") }
        current.messages.drop(index + 1).forEach { versionCache.remove(it.id) }
        store?.let { s ->
            current.conversationId?.let { conversationId ->
                current.messages.getOrNull(index + 1)?.let {
                    deleteFiles(s.deleteMessagesFrom(it.id, conversationId))
                }
                s.updateMessageContent(edited.id, content, "", now())
            }
        }
        appendAssistantPlaceholderAndGenerate()
    }

    fun newConversation() {
        stop()
        stashIfNoStore()
        failedAssistantId = null
        discardStagedAttachments()
        versionCache.clear()
        _uiState.update {
            // The draft belonged to the conversation being left; a fresh one
            // starts with an empty composer.
            it.copy(
                conversationId = null,
                messages = emptyList(),
                status = ChatStatus.Idle,
                draft = "",
                voiceStatus = VoiceStatus.Idle,
                voiceHint = false,
                searchEnabled = false,
                searchNotice = null,
            )
        }
    }

    /**
     * Opens a saved conversation: stops any active generation (its partial
     * content is persisted first), clears the leftover draft, and reloads
     * everything from the store — so this also restores state after process
     * death. Without a store, the in-session snapshot is used.
     */
    suspend fun openConversation(id: String) {
        if (_uiState.value.conversationId == id) return
        stop()
        generationJob?.join()
        stashIfNoStore()
        failedAssistantId = null
        discardStagedAttachments()
        versionCache.clear()
        val versionRows = store?.messageVersions(id)?.first().orEmpty()
        val versionsById = versionRows.groupBy({ it.messageId }, { it.content })
        val attachmentsById = store?.attachments(id)?.first().orEmpty()
            .groupBy({ it.messageId }, { it.toUiAttachment() })
        versionCache.loadAll(versionsById)
        val messages = store
            ?.messages(id)
            ?.first()
            ?.map { entity ->
                val versions = versionsById[entity.id].orEmpty()
                val selected = when {
                    versions.isEmpty() -> 0
                    else -> entity.selectedVersion.coerceIn(versions.indices)
                }
                UiMessage(
                    id = entity.id,
                    role = Role.valueOf(entity.role),
                    content = entity.content,
                    createdAt = entity.createdAt,
                    versions = versions,
                    selectedVersion = selected,
                    attachments = attachmentsById[entity.id].orEmpty(),
                    reasoning = entity.reasoning,
                    sources = searchResultsFromJson(entity.sources),
                )
            }
            ?: conversationSnapshots[id].orEmpty()
        // Answers restored with persisted sources re-arm their user
        // message's web context, so regenerate/retry keep grounding.
        val restored = messages.toMutableList()
        restored.forEachIndexed { index, message ->
            if (message.role == Role.ASSISTANT && message.sources.isNotEmpty()) {
                for (j in index - 1 downTo 0) {
                    if (restored[j].role == Role.USER) {
                        restored[j] = restored[j].copy(webResults = message.sources)
                        break
                    }
                }
            }
        }
        val searchEnabled = store?.conversation(id)?.searchEnabled ?: false
        _uiState.update { state ->
            state.copy(
                conversationId = id,
                messages = restored,
                status = ChatStatus.Idle,
                draft = "",
                voiceStatus = VoiceStatus.Idle,
                voiceHint = false,
                searchEnabled = searchEnabled,
                searchNotice = null,
            )
        }
    }

    /**
     * Removes a conversation from history. Deleting the open one also resets
     * the screen, so a later send cannot append to a missing conversation.
     */
    suspend fun deleteConversation(id: String) {
        if (_uiState.value.conversationId == id) {
            stop()
            failedAssistantId = null
            discardStagedAttachments()
            conversationSnapshots.remove(id)
            versionCache.clear()
            _uiState.update {
                it.copy(
                    conversationId = null,
                    messages = emptyList(),
                    status = ChatStatus.Idle,
                    draft = "",
                    voiceStatus = VoiceStatus.Idle,
                    voiceHint = false,
                    searchEnabled = false,
                    searchNotice = null,
                )
            }
        }
        deleteFiles(store?.deleteConversation(id).orEmpty())
    }

    /** The configured generation wiring, or null while setup is required. */
    private fun currentLlm(): ChatLlmState.Ready? = chatLlm.value as? ChatLlmState.Ready

    /** Shows the setup prompt; used when an intent needs the provider and none is configured. */
    private fun refuseWithoutLlm() {
        _uiState.update { it.copy(needsSetup = true) }
    }

    /**
     * Runs one generation: collects the provider stream into the assistant
     * placeholder, then settles into Idle or Error.
     *
     * On cancellation the partial content is persisted and kept, and the status
     * returns to Idle, so nothing gets stuck.
     */
    private suspend fun startGeneration(
        llm: ChatLlmState.Ready,
        assistantId: String,
    ) {
        val (requestMessages, images) = requestFor(assistantId)
        val question = _uiState.value.messages
            .dropLast(1)
            .lastOrNull { it.role == Role.USER }
            ?.content
            .orEmpty()
        val job = currentCoroutineContext().job
        generationJob = job
        lastPersistAt = clock()
        _uiState.update { it.copy(status = ChatStatus.Generating) }
        var failure: ChatChunk.Failure? = null
        try {
            withContext(generationDispatcher) {
                llm.provider
                    .stream(ChatRequest(model = llm.model, messages = requestMessages, images = images))
                    .collect { chunk ->
                        when (chunk) {
                            is ChatChunk.Delta -> {
                                appendDelta(assistantId, chunk.text)
                                maybePersist(assistantId)
                            }
                            is ChatChunk.Reasoning -> {
                                appendReasoning(assistantId, chunk.text)
                                maybePersist(assistantId)
                            }
                            is ChatChunk.Done -> Unit
                            is ChatChunk.Failure -> failure = chunk
                        }
                    }
            }
        } catch (e: CancellationException) {
            // Persist the partial content even though this coroutine is
            // cancelled (the store calls suspend, hence NonCancellable). The
            // partial answer also becomes a version: stopping keeps it as the
            // newest answer of the switcher.
            withContext(NonCancellable) {
                persistAssistantContent(assistantId)
                snapshotVersion(assistantId)
            }
            // Only settle the status if this generation is still the active one;
            // a newer generation may already have taken over.
            _uiState.update { state ->
                if (generationJob === job && state.status is ChatStatus.Generating) {
                    state.copy(status = ChatStatus.Idle)
                } else {
                    state
                }
            }
            throw e
        } catch (e: Exception) {
            failure = ChatChunk.Failure(ProviderError.Unknown, e.message)
        }
        val settled = failure
        if (settled == null) {
            failedAssistantId = null
            if (generationJob === job) generationJob = null
            persistAssistantContent(assistantId)
            snapshotVersion(assistantId)
            _uiState.update { it.copy(status = ChatStatus.Idle) }
            fetchFollowUps(llm, assistantId, question)
        } else {
            failGeneration(assistantId, settled)
        }
    }

    /** Best-effort: a failure or timeout leaves the answer without chips. */
    private suspend fun fetchFollowUps(llm: ChatLlmState.Ready, assistantId: String, question: String) {
        val suggester = followUpSuggestions ?: return
        val answer = _uiState.value.messages.firstOrNull { it.id == assistantId }?.content.orEmpty()
        if (question.isBlank() || !FollowUpSuggestions.isWorthSuggesting(answer)) return
        val suggestions = try {
            withTimeout(FollowUpSuggestions.TIMEOUT_MS) {
                suggester(llm.provider, llm.model, question, answer)
            }
        } catch (e: TimeoutCancellationException) {
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (suggestions.isEmpty()) return
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == assistantId) m.copy(followUps = suggestions) else m
            })
        }
        if (_uiState.value.messages.none { it.id == assistantId }) return
        store?.updateFollowUps(assistantId, suggestions.joinToString("\n            "))
    }

    private suspend fun appendAssistantPlaceholderAndGenerate() {
        val llm = currentLlm()
        if (llm == null) {
            refuseWithoutLlm()
            return
        }
        val assistantId = newId()
        _uiState.update { state ->
            state.copy(messages = state.messages + UiMessage(assistantId, Role.ASSISTANT, "", now()))
        }
        store?.let { s ->
            _uiState.value.conversationId?.let {
                s.appendMessage(UiMessage(assistantId, Role.ASSISTANT, "", now()).toEntity(it))
            }
        }
        startGeneration(llm, assistantId)
    }

    private suspend fun appendDelta(assistantId: String, text: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == assistantId) message.copy(content = message.content + text) else message
                },
            )
        }
    }

    /** Streams real model reasoning into the assistant message. */
    private suspend fun appendReasoning(assistantId: String, text: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == assistantId) message.copy(reasoning = message.reasoning + text) else message
                },
            )
        }
    }

    /**
     * Throttled persistence: writing every delta would thrash the database, so
     * streamed output lands at most once per [PERSIST_THROTTLE_MS] and always
     * again at generation end.
     */
    private suspend fun maybePersist(assistantId: String) {
        val timestamp = clock()
        if (timestamp - lastPersistAt >= PERSIST_THROTTLE_MS) {
            lastPersistAt = timestamp
            persistAssistantContent(assistantId)
        }
    }

    /** Writes the current streamed content of [assistantId] to the store. */
    private suspend fun persistAssistantContent(assistantId: String) {
        val s = store ?: return
        val state = _uiState.value
        val conversationId = state.conversationId ?: return
        val message = state.messages.firstOrNull { it.id == assistantId } ?: return
        s.updateMessageContent(assistantId, message.content, message.reasoning, now())
    }

    /**
     * Records the current content of [messageId] as its newest answer version
     * and selects it. A consecutive duplicate of the last version is not
     * recorded twice.
     */
    private suspend fun snapshotVersion(messageId: String) {
        val content = _uiState.value.messages
            .firstOrNull { it.id == messageId }
            ?.takeIf { it.role == Role.ASSISTANT }
            ?.content
            ?: return
        if (content.isBlank()) return
        val appended = versionCache.append(messageId, content)
        if (appended) {
            store?.saveVersion(messageId, content)
        }
        val versions = versionCache.versionsOf(messageId).orEmpty()
        val selected = versions.lastIndex
        _uiState.update { state ->
            state.copy(messages = state.messages.map { m ->
                if (m.id == messageId) {
                    m.copy(content = content, versions = versions.toList(), selectedVersion = selected)
                } else {
                    m
                }
            })
        }
        store?.updateSelectedVersion(messageId, selected)
    }


    private suspend fun failGeneration(assistantId: String, failure: ChatChunk.Failure) {
        // An empty placeholder carries no information: drop it. Partial
        // content is kept so the user sees what arrived. Both decisions are
        // computed inside one state update.
        var kept = false
        _uiState.update { state ->
            val hasContent = state.messages.any { it.id == assistantId && it.content.isNotEmpty() }
            kept = hasContent
            state.copy(
                messages = if (hasContent) state.messages else state.messages.filterNot { it.id == assistantId },
                status = ChatStatus.Error(failure.error.userMessage),
            )
        }
        val conversationId = _uiState.value.conversationId
        if (store != null && conversationId != null) {
            if (kept) {
                persistAssistantContent(assistantId)
            } else {
                deleteFiles(store.deleteMessagesFrom(assistantId, conversationId))
            }
        }
        failedAssistantId = if (kept) assistantId else null
    }

    /**
     * The request for one generation: every message before the assistant
     * placeholder. Images and text-file contents of the most recent user
     * message ride along; older attachments stay display-only to keep repeated
     * turns bounded.
     */
    private suspend fun requestFor(assistantId: String): Pair<List<Pair<Role, String>>, List<String>> =
        withContext(generationDispatcher) {
            val messages = _uiState.value.messages.takeWhile { it.id != assistantId }
            val lastUserId = messages.lastOrNull { it.role == Role.USER }?.id
            var images: List<String> = emptyList()
            val mapped = messages.map { message ->
                if (message.id != lastUserId ||
                    (message.attachments.isEmpty() && message.webResults.isEmpty())
                ) {
                    message.role to message.content
                } else {
                    val textFiles = message.attachments.filter { it.kind == UiAttachment.Kind.TEXT }
                    images = message.attachments
                        .filter { it.kind == UiAttachment.Kind.IMAGE }
                        .mapNotNull { dataUrl(it.path) }
                    val inline = textFiles.joinToString("\n\n") { file ->
                        "[File: ${file.displayName}]\n${readTextFile(file.path)}"
                    }
                    val webBlock = if (message.webResults.isEmpty()) {
                        ""
                    } else {
                        "[Web results]\n" + message.webResults.mapIndexed { index, result ->
                            "${index + 1}. ${result.title} — ${result.url}\n${result.snippet}"
                        }.joinToString("\n")
                    }
                    val parts = buildList {
                        if (message.content.isNotBlank()) add(message.content)
                        if (inline.isNotEmpty()) add(inline)
                        if (webBlock.isNotEmpty()) add(webBlock)
                    }
                    message.role to parts.joinToString("\n\n")
                }
            }
            mapped to images
        }

    /** The data-URL of a stored image copy, or null when the file is missing. */
    private fun dataUrl(path: String): String? {
        val file = File(path)
        if (!file.exists()) return null
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

    /** The content of a stored text-file copy, or empty when it is missing. */
    private fun readTextFile(path: String): String =
        File(path).takeIf { it.exists() }?.readText().orEmpty()

    private fun deleteFiles(paths: List<String>) {
        if (attachmentsDir == null) return
        paths.forEach { path -> runCatching { File(path).delete() } }
    }

    private fun isGenerating(): Boolean = generationJob?.isActive == true

    private fun stashIfNoStore() {
        if (store != null) return
        val state = _uiState.value
        val id = state.conversationId ?: return
        conversationSnapshots[id] = state.messages
    }

    private fun UiAttachment.toEntity(messageId: String, conversationId: String, createdAt: Long) =
        AttachmentEntity(
            id = id,
            messageId = messageId,
            conversationId = conversationId,
            kind = kind.name,
            displayName = displayName,
            mime = mime,
            path = path,
            sizeBytes = sizeBytes,
            createdAt = createdAt,
        )

    private fun AttachmentEntity.toUiAttachment() = UiAttachment(
        id = id,
        kind = UiAttachment.Kind.valueOf(kind),
        displayName = displayName,
        mime = mime,
        path = path,
        sizeBytes = sizeBytes,
    )

    private fun UiMessage.toEntity(conversationId: String) = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        content = content,
        createdAt = createdAt,
        selectedVersion = selectedVersion,
    )

    private fun newId(): String = UUID.randomUUID().toString()

    private fun now(): Long = clock()

    companion object {
        /** Minimum interval between streamed-content writes to the store. */
        const val PERSIST_THROTTLE_MS = 300L

        /** The plain-text transcript format used for sharing a conversation. */
        fun buildShareText(title: String, messages: List<UiMessage>): String = buildString {
            append(title)
            messages.forEach { message ->
                when (message.role) {
                    Role.USER -> if (message.content.isNotBlank()) {
                        append("\n\nYou: ").append(message.content)
                    }
                    Role.ASSISTANT -> if (message.content.isNotBlank()) {
                        append("\n\nAssistant: ").append(message.content)
                    }
                    Role.SYSTEM -> Unit
                }
            }
        }

        /** Shown when search is toggled on without a configured service. */
        const val SEARCH_NOT_CONFIGURED = "Configure a search service in Settings first."

        /** Per-message attachment limits, enforced on the staged list. */
        const val MAX_IMAGES_PER_MESSAGE = AttachmentIngester.MAX_IMAGES_PER_MESSAGE
        const val MAX_TEXTS_PER_MESSAGE = AttachmentIngester.MAX_TEXTS_PER_MESSAGE

    }
}
