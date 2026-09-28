package com.assistant.app.data.attachments

import com.assistant.app.data.AttachmentIngester
import com.assistant.app.data.local.AttachmentEntity
import com.assistant.app.llm.model.UiAttachment
import java.io.File

/**
 * Manages staged pending attachments, limit enforcement, and file cleanup on disk.
 */
class AttachmentManager(
    private val attachmentsDir: File? = null
) {
    fun addPendingAttachments(
        current: List<UiAttachment>,
        incoming: List<UiAttachment>
    ): Pair<List<UiAttachment>, String?> {
        if (incoming.isEmpty()) return current to null
        val images = current.count { it.kind == UiAttachment.Kind.IMAGE } +
            incoming.count { it.kind == UiAttachment.Kind.IMAGE }
        val texts = current.count { it.kind == UiAttachment.Kind.TEXT } +
            incoming.count { it.kind == UiAttachment.Kind.TEXT }

        return when {
            images > MAX_IMAGES_PER_MESSAGE -> current to "Up to $MAX_IMAGES_PER_MESSAGE images per message."
            texts > MAX_TEXTS_PER_MESSAGE -> current to "Up to $MAX_TEXTS_PER_MESSAGE text files per message."
            else -> (current + incoming) to null
        }
    }

    fun removePendingAttachment(
        current: List<UiAttachment>,
        id: String
    ): List<UiAttachment> {
        val removed = current.firstOrNull { it.id == id } ?: return current
        deleteFile(removed.path)
        return current.filterNot { it.id == id }
    }

    fun discardStagedAttachments(staged: List<UiAttachment>) {
        if (staged.isEmpty()) return
        staged.forEach { deleteFile(it.path) }
    }

    fun deleteFile(path: String) {
        if (attachmentsDir != null) {
            runCatching { File(path).delete() }
        }
    }

    fun deleteFiles(paths: List<String>) {
        if (attachmentsDir == null) return
        paths.forEach { path -> runCatching { File(path).delete() } }
    }

    fun toEntity(attachment: UiAttachment, messageId: String, conversationId: String, createdAt: Long) =
        AttachmentEntity(
            id = attachment.id,
            messageId = messageId,
            conversationId = conversationId,
            kind = attachment.kind.name,
            displayName = attachment.displayName,
            mime = attachment.mime,
            path = attachment.path,
            sizeBytes = attachment.sizeBytes,
            createdAt = createdAt,
        )

    fun toUiAttachment(entity: AttachmentEntity) = UiAttachment(
        id = entity.id,
        kind = UiAttachment.Kind.valueOf(entity.kind),
        displayName = entity.displayName,
        mime = entity.mime,
        path = entity.path,
        sizeBytes = entity.sizeBytes,
    )

    companion object {
        const val MAX_IMAGES_PER_MESSAGE = AttachmentIngester.MAX_IMAGES_PER_MESSAGE
        const val MAX_TEXTS_PER_MESSAGE = AttachmentIngester.MAX_TEXTS_PER_MESSAGE
    }
}
