package com.assistant.app.data

/**
 * In-memory working copy of the loaded conversation's answer versions
 *, keyed by assistant message id in answer order. The
 * store persists the rows; this is the working copy the version switcher
 * reads and writes. Lives only for the currently open conversation.
 */
class AnswerVersions {

    private val cache = HashMap<String, MutableList<String>>()

    fun versionsOf(messageId: String): List<String>? = cache[messageId]?.toList()

    /** Appends [content] unless it duplicates the newest answer; true if appended. */
    fun append(messageId: String, content: String): Boolean {
        val versions = cache.getOrPut(messageId) { mutableListOf() }
        if (versions.lastOrNull() == content) return false
        versions.add(content)
        return true
    }

    fun remove(messageId: String) {
        cache.remove(messageId)
    }

    fun clear() = cache.clear()

    fun loadAll(versionsByMessage: Map<String, List<String>>) {
        versionsByMessage.forEach { (messageId, contents) ->
            cache[messageId] = contents.toMutableList()
        }
    }
}
