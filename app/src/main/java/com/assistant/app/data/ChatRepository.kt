package com.assistant.app.data

import android.util.Base64
import com.assistant.app.data.attachments.AttachmentManager
import com.assistant.app.data.generation.GenerationController
import com.assistant.app.data.local.ConversationEntity
import com.assistant.app.data.local.MessageEntity
import com.assistant.app.data.search.SearchController
import com.assistant.app.data.versions.AnswerVersionStore
import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.Role
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
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/**
 * High-level orchestrator for chat operations: session state, draft management,
 * generation streaming, persistence, web search, answer versions, and attachments.
 */
class ChatRepository(
    private val chatLlm: StateFlow<ChatLlmState>,
    private val generationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val store: ConversationStore? = null,
    private val followUpSuggestions: (suspend (LlmProvider, String, String, String) -> List<String>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    internal val attachmentsDir: File? = null,
    webSearch: (suspend (String) -> SearchOutcome?)? = null,
) {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val attachmentManager = AttachmentManager(attachmentsDir)
    private val searchController = SearchController(webSearch)
    private val versionStore = AnswerVersionStore(store, clock)
    private val generationController = GenerationController(generationDispatcher, clock)

    /** The failed generation's message id while it is still shown with an error. */
    private var failedAssistantId: String? = null

    /** Conversation snapshots for the no-persistence (test) mode. */
    private val conversationSnapshots = LinkedHashMap<String, List<UiMessage>>()

    /** Saved conversations for the history screen, newest activity first. */
    val conversations: Flow<List<ConversationEntity>> = store?.conversations() ?: emptyFlow()

    fun setDraft(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    fun setNeedsSetup(needsSetup: Boolean) {
        _uiState.update { it.copy(needsSetup = needsSetup) }
    }

    fun setVoiceStatus(status: VoiceStatus) {
        _uiState.update { it.copy(voiceStatus = status) }
    }

    fun setVoiceHint(show: Boolean) {
        _uiState.update { it.copy(voiceHint = show) }
    }

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

    fun addPendingAttachments(attachments: List<UiAttachment>) {
        _uiState.update { state ->
            val (updated, error) = attachmentManager.addPendingAttachments(state.pendingAttachments, attachments)
            state.copy(
                pendingAttachments = updated,
                attachmentError = error,
                isIngestingAttachments = false
            )
        }
    }

    fun setIngestingAttachments(ingesting: Boolean) {
        _uiState.update { it.copy(isIngestingAttachments = ingesting) }
    }

    fun removePendingAttachment(id: String) {
        _uiState.update { state ->
            val updated = attachmentManager.removePendingAttachment(state.pendingAttachments, id)
            state.copy(pendingAttachments = updated)
        }
    }

    fun clearAttachmentError() {
        _uiState.update { it.copy(attachmentError = null) }
    }

    fun setAttachmentError(message: String) {
        _uiState.update { it.copy(attachmentError = message, isIngestingAttachments = false) }
    }

    private fun clearPendingAttachments() {
        _uiState.update { it.copy(pendingAttachments = emptyList()) }
    }

    private fun discardStagedAttachments() {
        val staged = _uiState.value.pendingAttachments
        if (staged.isEmpty()) return
        _uiState.update { it.copy(pendingAttachments = emptyList(), attachmentError = null) }
        attachmentManager.discardStagedAttachments(staged)
    }

    suspend fun shareConversationText(id: String): String? {
        val s = store ?: return null
        val title = s.conversationTitle(id) ?: return null
        val messages = s.messages(id).first()
            .map { UiMessage(it.id, Role.valueOf(it.role), it.content, it.createdAt) }
        if (messages.none { it.content.isNotBlank() }) return null
        return buildShareText(title, messages)
    }

    private fun dropTrailingEmptyAssistant(messages: MutableList<UiMessage>): MutableList<UiMessage> {
        while (messages.isNotEmpty()) {
            val last = messages.last()
            val isEmptyAnswer = last.role == Role.ASSISTANT &&
                last.content.isBlank() &&
                last.reasoning.isBlank() &&
                last.sources.isEmpty()
            if (!isEmptyAnswer) break
            messages.removeAt(messages.lastIndex)
        }
        return messages
    }

    suspend fun setSearchEnabled(enabled: Boolean) {
        _uiState.update { it.copy(searchEnabled = enabled, searchNotice = null) }
        store?.let { s ->
            _uiState.value.conversationId?.let { s.setSearchEnabled(it, enabled) }
        }
    }

    private fun setSearchNotice(message: String?) {
        _uiState.update { it.copy(searchNotice = message) }
    }

    fun dismissSearchNotice() {
        _uiState.update { it.copy(searchNotice = null) }
    }

    private suspend fun runWebSearch(query: String): List<SearchResult> {
        val result = try {
            searchController.executeSearch(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (result != null && result.noticeMessage != null) {
            setSearchNotice(result.noticeMessage)
        }
        return result?.results ?: emptyList()
    }

    fun stop() {
        generationController.stop()
    }

    suspend fun send(text: String) {
        val content = text.trim()
        if ((content.isEmpty() && _uiState.value.pendingAttachments.isEmpty()) || generationController.isGenerating) return
        cleanupFailedAssistantMessage()
        val job = currentCoroutineContext().job
        try {
            sendPrepared(text, content, job)
        } catch (e: CancellationException) {
            _uiState.update { state ->
                if (state.status is ChatStatus.Generating) {
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
            return
        }
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
            attachments.forEach { s.appendAttachment(attachmentManager.toEntity(it, userMessage.id, conversationId, now())) }
            if (webResults.isNotEmpty()) {
                s.updateSources(assistantId, webResults.toSearchJson())
            }
        }
        clearPendingAttachments()
        startGeneration(llm, assistantId, job)
    }

    suspend fun retry() {
        if (_uiState.value.status !is ChatStatus.Error || generationController.isGenerating) return
        if (currentLlm() == null) {
            refuseWithoutLlm()
            return
        }
        cleanupFailedAssistantMessage()
        if (_uiState.value.messages.none { it.role == Role.USER }) return
        appendAssistantPlaceholderAndGenerate(currentCoroutineContext().job)
    }

    fun dismissError() {
        if (_uiState.value.status is ChatStatus.Error) {
            _uiState.update { it.copy(status = ChatStatus.Idle) }
        }
    }

    suspend fun regenerate() {
        val state = _uiState.value
        val last = state.messages.lastOrNull()
        if (state.status !is ChatStatus.Idle || generationController.isGenerating || last?.role != Role.ASSISTANT) return
        val llm = currentLlm() ?: run {
            refuseWithoutLlm()
            return
        }
        val job = currentCoroutineContext().job
        versionStore.snapshotVersion(last.id, state.messages) { transform ->
            _uiState.update { s -> s.copy(messages = transform(s.messages)) }
        }
        _uiState.update { s ->
            s.copy(messages = s.messages.map { m ->
                if (m.id == last.id) m.copy(content = "", reasoning = "") else m
            })
        }
        store?.let { s -> state.conversationId?.let { s.updateMessageContent(last.id, "", "", now()) } }
        startGeneration(llm, last.id, job)
    }

    suspend fun switchVersion(messageId: String, index: Int) {
        if (_uiState.value.status !is ChatStatus.Idle || generationController.isGenerating) return
        versionStore.snapshotVersion(messageId, _uiState.value.messages) { transform ->
            _uiState.update { s -> s.copy(messages = transform(s.messages)) }
        }
        versionStore.switchVersion(messageId, index) { transform ->
            _uiState.update { s -> s.copy(messages = transform(s.messages)) }
        }
    }

    suspend fun setConversationPinned(id: String, pinned: Boolean) {
        store?.setPinned(id, pinned)
    }

    suspend fun renameConversation(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        store?.renameConversation(id, trimmed)
    }

    suspend fun editAndResend(messageId: String, newContent: String) {
        val content = newContent.trim()
        if (content.isEmpty() || generationController.isGenerating) return
        if (currentLlm() == null) {
            refuseWithoutLlm()
            return
        }
        val job = currentCoroutineContext().job
        val current = _uiState.value
        val index = current.messages.indexOfFirst { it.id == messageId && it.role == Role.USER }
        if (index < 0) return
        cleanupFailedAssistantMessage()
        val edited = current.messages[index].copy(content = content)
        _uiState.update { it.copy(messages = it.messages.take(index) + edited, draft = "") }
        current.messages.drop(index + 1).forEach { versionStore.remove(it.id) }
        store?.let { s ->
            current.conversationId?.let { conversationId ->
                current.messages.getOrNull(index + 1)?.let {
                    attachmentManager.deleteFiles(s.deleteMessagesFrom(it.id, conversationId))
                }
                s.updateMessageContent(edited.id, content, "", now())
            }
        }
        appendAssistantPlaceholderAndGenerate(job)
    }

    fun newConversation() {
        stop()
        stashIfNoStore()
        failedAssistantId = null
        discardStagedAttachments()
        versionStore.clear()
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

    suspend fun openConversation(id: String) {
        if (_uiState.value.conversationId == id) return
        stop()
        generationController.joinActive()
        stashIfNoStore()
        failedAssistantId = null
        discardStagedAttachments()
        versionStore.clear()
        val versionRows = store?.messageVersions(id)?.first().orEmpty()
        val versionsById = versionRows.groupBy({ it.messageId }, { it.content })
        val attachmentsById = store?.attachments(id)?.first().orEmpty()
            .groupBy({ it.messageId }, { attachmentManager.toUiAttachment(it) })
        versionStore.loadAll(versionsById)
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
        val restored = dropTrailingEmptyAssistant(messages.toMutableList())
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

    suspend fun deleteConversation(id: String) {
        if (_uiState.value.conversationId == id) {
            stop()
            failedAssistantId = null
            discardStagedAttachments()
            conversationSnapshots.remove(id)
            versionStore.clear()
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
        attachmentManager.deleteFiles(store?.deleteConversation(id).orEmpty())
    }

    private fun currentLlm(): ChatLlmState.Ready? = chatLlm.value as? ChatLlmState.Ready

    private fun refuseWithoutLlm() {
        _uiState.update { it.copy(needsSetup = true) }
    }

    private suspend fun startGeneration(
        llm: ChatLlmState.Ready,
        assistantId: String,
        job: Job,
    ) {
        val targetConversationId = _uiState.value.conversationId ?: return
        val session = generationController.tryStartSession(targetConversationId, assistantId, job)
            ?: return

        val (requestMessages, images) = requestFor(assistantId)
        val question = _uiState.value.messages
            .dropLast(1)
            .lastOrNull { it.role == Role.USER }
            ?.content
            .orEmpty()

        _uiState.update { it.copy(status = ChatStatus.Generating) }

        generationController.runStream(
            session = session,
            provider = llm.provider,
            request = ChatRequest(model = llm.model, messages = requestMessages, images = images),
            onDelta = { delta ->
                if (_uiState.value.conversationId == targetConversationId) {
                    appendDelta(assistantId, delta)
                }
            },
            onReasoning = { reasoning ->
                if (_uiState.value.conversationId == targetConversationId) {
                    appendReasoning(assistantId, reasoning)
                }
            },
            onPersist = {
                persistAssistantContent(assistantId, targetConversationId)
            },
            onCancelled = {
                persistAssistantContent(assistantId, targetConversationId)
                if (_uiState.value.conversationId == targetConversationId) {
                    versionStore.snapshotVersion(assistantId, _uiState.value.messages) { transform ->
                        _uiState.update { s -> s.copy(messages = transform(s.messages)) }
                    }
                    _uiState.update { state ->
                        if (state.status is ChatStatus.Generating) state.copy(status = ChatStatus.Idle) else state
                    }
                }
            },
            onFailure = { failure ->
                failGeneration(assistantId, targetConversationId, failure)
            },
            onSuccess = {
                failedAssistantId = null
                persistAssistantContent(assistantId, targetConversationId)
                if (_uiState.value.conversationId == targetConversationId) {
                    versionStore.snapshotVersion(assistantId, _uiState.value.messages) { transform ->
                        _uiState.update { s -> s.copy(messages = transform(s.messages)) }
                    }
                    _uiState.update { it.copy(status = ChatStatus.Idle) }
                }
                fetchFollowUps(llm, assistantId, question, targetConversationId)
            }
        )
    }

    private suspend fun fetchFollowUps(
        llm: ChatLlmState.Ready,
        assistantId: String,
        question: String,
        targetConversationId: String,
    ) {
        val suggester = followUpSuggestions ?: return
        if (_uiState.value.conversationId != targetConversationId) return
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
        if (_uiState.value.conversationId == targetConversationId) {
            _uiState.update { s ->
                s.copy(messages = s.messages.map { m ->
                    if (m.id == assistantId) m.copy(followUps = suggestions) else m
                })
            }
        }
        store?.updateFollowUps(assistantId, suggestions.joinToString("\n            "))
    }

    private suspend fun appendAssistantPlaceholderAndGenerate(job: Job) {
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
        startGeneration(llm, assistantId, job)
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

    private suspend fun persistAssistantContent(assistantId: String, targetConversationId: String? = null) {
        val s = store ?: return
        val conversationId = targetConversationId ?: _uiState.value.conversationId ?: return
        val message = _uiState.value.messages.firstOrNull { it.id == assistantId }
        if (message != null) {
            s.updateMessageContent(assistantId, message.content, message.reasoning, now())
        }
    }

    private suspend fun cleanupFailedAssistantMessage() {
        val failedId = failedAssistantId ?: return
        failedAssistantId = null
        _uiState.update { state ->
            state.copy(messages = state.messages.filterNot { it.id == failedId })
        }
        versionStore.remove(failedId)
        store?.let { s ->
            _uiState.value.conversationId?.let { conversationId ->
                attachmentManager.deleteFiles(s.deleteMessagesFrom(failedId, conversationId))
            }
        }
    }

    private suspend fun failGeneration(
        assistantId: String,
        targetConversationId: String,
        failure: ChatChunk.Failure
    ) {
        var kept = false
        if (_uiState.value.conversationId == targetConversationId) {
            _uiState.update { state ->
                val hasContent = state.messages.any { it.id == assistantId && it.content.isNotEmpty() }
                kept = hasContent
                state.copy(
                    messages = if (hasContent) state.messages else state.messages.filterNot { it.id == assistantId },
                    status = ChatStatus.Error(failure.error.userMessage),
                )
            }
        }
        if (store != null) {
            if (kept) {
                persistAssistantContent(assistantId, targetConversationId)
            } else {
                attachmentManager.deleteFiles(store.deleteMessagesFrom(assistantId, targetConversationId))
            }
        }
        failedAssistantId = if (kept) assistantId else null
    }

    private suspend fun requestFor(assistantId: String): Pair<List<Pair<Role, String>>, List<String>> =
        withContext(generationDispatcher) {
            val messages = _uiState.value.messages
                .takeWhile { it.id != assistantId }
                .filterNot { it.id == failedAssistantId } // Exclude failed partial assistant responses from LLM context
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

    private fun stashIfNoStore() {
        if (store != null) return
        val state = _uiState.value
        val id = state.conversationId ?: return
        conversationSnapshots[id] = state.messages
    }

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

        const val SEARCH_NOT_CONFIGURED = SearchController.SEARCH_NOT_CONFIGURED
        const val MAX_IMAGES_PER_MESSAGE = AttachmentManager.MAX_IMAGES_PER_MESSAGE
        const val MAX_TEXTS_PER_MESSAGE = AttachmentManager.MAX_TEXTS_PER_MESSAGE
    }
}
