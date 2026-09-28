package com.assistant.app.data

import androidx.room.withTransaction
import com.assistant.app.data.local.AttachmentEntity
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.local.ConversationEntity
import com.assistant.app.data.local.MessageEntity
import com.assistant.app.data.local.MessageVersionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Conversation persistence: title generation, the rule that a message write
 * bumps the conversation timestamp, and the transactional delete paths (which
 * also clean up answer-version rows). Nothing else touches the database.
 */
class ConversationStore(private val db: ChatDatabase) {

    /** All conversations, pinned first, then newest activity. */
    fun conversations(): Flow<List<ConversationEntity>> = db.conversationDao().observeAll()

    fun messages(conversationId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeForConversation(conversationId)

    fun messageVersions(conversationId: String): Flow<List<MessageVersionEntity>> =
        db.messageDao().observeVersionsForConversation(conversationId)

    suspend fun createConversation(id: String, title: String, now: Long) {
        db.conversationDao().upsert(ConversationEntity(id = id, title = title, createdAt = now, updatedAt = now))
    }

    suspend fun appendMessage(message: MessageEntity) {
        db.messageDao().insert(message)
    }

    /** Replaces a message's content and reasoning, and marks the conversation active. */
    suspend fun updateMessageContent(id: String, content: String, reasoning: String, updatedAt: Long) {
        db.withTransaction {
            db.messageDao().updateContent(id, content, reasoning)
            db.conversationDao().touchConversationOf(id, updatedAt)
        }
    }

    suspend fun saveVersion(messageId: String, content: String) {
        db.messageDao().insertVersion(MessageVersionEntity(messageId = messageId, content = content))
    }

    suspend fun updateSelectedVersion(messageId: String, index: Int) {
        db.messageDao().updateSelectedVersion(messageId, index)
    }

    suspend fun updateSources(messageId: String, sources: String?) {
        db.messageDao().updateSources(messageId, sources)
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        db.conversationDao().setPinned(id, pinned)
    }

    suspend fun renameConversation(id: String, title: String) {
        db.conversationDao().rename(id, title)
    }

    fun attachments(conversationId: String): Flow<List<AttachmentEntity>> =
        db.attachmentDao().observeForConversation(conversationId)

    suspend fun appendAttachment(attachment: AttachmentEntity) {
        db.attachmentDao().insert(attachment)
    }

    suspend fun conversationTitle(id: String): String? = db.conversationDao().titleOf(id)

    suspend fun conversation(id: String): ConversationEntity? = db.conversationDao().byId(id)

    suspend fun setSearchEnabled(id: String, enabled: Boolean) {
        db.conversationDao().setSearchEnabled(id, enabled)
    }

    /**
     * Removes [messageId] and every message after it, with their version and
     * attachment rows. Returns the attachment paths so the caller can delete
     * the stored copies from disk.
     */
    suspend fun deleteMessagesFrom(messageId: String, conversationId: String): List<String> {
        val paths = db.withTransaction {
            val doomed = db.attachmentDao().forMessagesFrom(messageId, conversationId)
            db.attachmentDao().deleteFrom(messageId, conversationId)
            db.messageDao().deleteVersionsFrom(messageId, conversationId)
            db.messageDao().deleteFrom(messageId, conversationId)
            doomed.map { it.path }
        }
        return paths
    }

    /** Deletes a conversation and all of its rows; returns the attachment paths. */
    suspend fun updateFollowUps(messageId: String, followUps: String?) {
        db.messageDao().updateFollowUps(messageId, followUps)
    }

    suspend fun deleteConversation(id: String): List<String> {
        val paths = db.withTransaction {
            val doomed = db.attachmentDao().pathsForConversation(id)
            db.attachmentDao().deleteForConversation(id)
            db.messageDao().deleteVersionsForConversation(id)
            db.messageDao().deleteForConversation(id)
            db.conversationDao().delete(id)
            doomed
        }
        return paths
    }

    /**
     * List title: the first user message truncated to [TITLE_MAX_LENGTH], or
     * "New conversation" when no user text arrived.
     */
    fun titleFor(firstUserText: String): String {
        val trimmed = firstUserText.trim()
        if (trimmed.isEmpty()) return NEW_CONVERSATION_TITLE
        if (trimmed.length <= TITLE_MAX_LENGTH) return trimmed
        // Truncate on code-point boundaries: a plain UTF-16 take could split a
        // surrogate pair and end the title with a broken character.
        var title = trimmed.take(TITLE_MAX_LENGTH)
        if (Character.isHighSurrogate(title.last()) && Character.isLowSurrogate(trimmed[TITLE_MAX_LENGTH])) {
            title = title.dropLast(1)
        }
        return title
    }

    companion object {
        const val TITLE_MAX_LENGTH = 48
        const val NEW_CONVERSATION_TITLE = "New conversation"
    }
}
