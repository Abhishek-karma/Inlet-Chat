package com.assistant.app.data

import android.util.Base64
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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

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
        if ((content.isEmpty() && _uiState.value.pendingAttachments.isEmpty()) || isGenerating()) return
        val job = currentCoroutineContext().job
        generationJob = job
        _uiState.update { it.copy(status = ChatStatus.Generating) }
        try {
            sendPrepared(text, content, job)
        } catch (e: CancellationException) {
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
     * Shows answer [index] of an assistant message.
     */
    suspend fun switchVersion(messageId: String, index: Int) {
        if (_uiState.value.status !is ChatStatus.Idle || isGenerating()) return
        val versions = versionCache.versionsOf(messageId) ?: return
        if (index !in versions.indices) return
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
     * Truncates the conversation at the given user message, replaces its content, and re-requests.
     */
    suspend fun editAndResend(messageId: String, newContent: String) {
        val content = newContent.trim()
        if (content.isEmpty() || isGenerating()) return
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
     * Opens a saved conversation: stops active generation, clears leftover draft,
     * and reloads everything from the store.
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
     * Removes a conversation from history.
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

    private fun currentLlm(): ChatLlmState.Ready? = chatLlm.value as? ChatLlmState.Ready

    private fun refuseWithoutLlm() {
        _uiState.update { it.copy(needsSetup = true) }
    }

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
            withContext(NonCancellable) {
                persistAssistantContent(assistantId)
                snapshotVersion(assistantId)
            }
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

    private suspend fun fetchFollowUps(llm: ChatLlmState.Ready, assistantId: String, question: String) {
        val suggester = followUpSuggestions ?: return
        val answer = _uiState.value.messages.firstOrNull { it.id == assistantId }?.content.orEmpty()
        if (question.isBlank() || !FollowUpSuggestions.isWorthSuggesting(answer)) return
        val suggestions = try {
            withTimeout(FollowUpSuggestions.TIMEOUT_MS) {
                suggester(llm.provider, llm.model, question, answer)
            }
        } catch (_: TimeoutCancellationException) {
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
        val llm = currentLlm() ?: run {
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

    private fun appendDelta(assistantId: String, text: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == assistantId) message.copy(content = message.content + text) else message
                },
            )
        }
    }

    private fun appendReasoning(assistantId: String, text: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == assistantId) message.copy(reasoning = message.reasoning + text) else message
                },
            )
        }
    }

    private suspend fun maybePersist(assistantId: String) {
        val timestamp = clock()
        if (timestamp - lastPersistAt >= PERSIST_THROTTLE_MS) {
            lastPersistAt = timestamp
            persistAssistantContent(assistantId)
        }
    }

    private suspend fun persistAssistantContent(assistantId: String) {
        val s = store ?: return
        val state = _uiState.value
        val conversationId = state.conversationId ?: return
        val message = state.messages.firstOrNull { it.id == assistantId } ?: return
        s.updateMessageContent(assistantId, message.content, message.reasoning, now())
    }

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

    private fun dataUrl(path: String): String? {
        val file = File(path)
        if (!file.exists()) return null
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }

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
        const val PERSIST_THROTTLE_MS = 300L

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

        const val SEARCH_NOT_CONFIGURED = "Configure a search service in Settings first."
        const val MAX_IMAGES_PER_MESSAGE = AttachmentIngester.MAX_IMAGES_PER_MESSAGE
        const val MAX_TEXTS_PER_MESSAGE = AttachmentIngester.MAX_TEXTS_PER_MESSAGE
    }
}
