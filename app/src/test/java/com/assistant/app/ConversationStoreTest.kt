package com.assistant.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.ConversationStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.local.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room round-trip behavior of [ConversationStore]: append/read, streamed
 * content updates, truncation on edit, deletion, title generation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationStoreTest {

    private val db: ChatDatabase = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val store = ConversationStore(db)

    @After
    fun tearDown() {
        db.close()
    }

    private fun message(id: String, conversationId: String, role: String, content: String, createdAt: Long) =
        MessageEntity(id = id, conversationId = conversationId, role = role, content = content, createdAt = createdAt)

    @Test
    fun appendMessagesReadsBackInChronologicalOrder() = runTest {
        store.createConversation("c1", "Title", now = 1_000)
        store.appendMessage(message("m1", "c1", "USER", "first", 1_000))
        store.appendMessage(message("m2", "c1", "ASSISTANT", "second", 2_000))
        store.appendMessage(message("m3", "c1", "USER", "third", 3_000))

        val messages = store.messages("c1").first()
        assertEquals(listOf("m1", "m2", "m3"), messages.map { it.id })
        assertEquals(listOf("first", "second", "third"), messages.map { it.content })
        assertEquals(listOf("USER", "ASSISTANT", "USER"), messages.map { it.role })
    }

    @Test
    fun updateMessageContentReplacesContentAndBumpsConversation() = runTest {
        store.createConversation("c1", "Old", now = 1_000)
        store.createConversation("c2", "Other", now = 500)
        store.appendMessage(message("m1", "c1", "ASSISTANT", "partial", 1_000))

        store.updateMessageContent("m1", "partial plus more", reasoning = "", updatedAt = 9_000)

        val messages = store.messages("c1").first()
        assertEquals("partial plus more", messages.single().content)
        val conversations = store.conversations().first()
        assertEquals("c1", conversations.first().id)
        assertEquals(9_000, conversations.first().updatedAt)
    }

    @Test
    fun deleteMessagesFromRemovesTargetAndEverythingAfter() = runTest {
        store.createConversation("c1", "Title", now = 1_000)
        store.appendMessage(message("m1", "c1", "USER", "keep", 1_000))
        store.appendMessage(message("m2", "c1", "ASSISTANT", "keep", 2_000))
        store.appendMessage(message("m3", "c1", "USER", "drop", 3_000))
        store.appendMessage(message("m4", "c1", "ASSISTANT", "drop", 4_000))

        store.deleteMessagesFrom("m3", "c1")

        assertEquals(listOf("m1", "m2"), store.messages("c1").first().map { it.id })
    }

    @Test
    fun deleteConversationRemovesConversationAndMessages() = runTest {
        store.createConversation("c1", "Title", now = 1_000)
        store.appendMessage(message("m1", "c1", "USER", "hello", 1_000))
        store.appendMessage(message("m2", "c1", "ASSISTANT", "hi", 2_000))
        store.createConversation("c2", "Survivor", now = 3_000)

        store.deleteConversation("c1")

        assertEquals(listOf("c2"), store.conversations().first().map { it.id })
        assertEquals(emptyList<MessageEntity>(), store.messages("c1").first())
    }

    @Test
    fun conversationsAreOrderedNewestFirst() = runTest {
        store.createConversation("old", "Old", now = 1_000)
        store.createConversation("new", "New", now = 5_000)
        store.createConversation("mid", "Mid", now = 3_000)

        assertEquals(listOf("new", "mid", "old"), store.conversations().first().map { it.id })
    }

    @Test
    fun titleUsesFirstUserMessageTruncatedTo48Chars() {
        val long = "a".repeat(60) + " tail"
        assertEquals("a".repeat(48), store.titleFor(long))
        assertEquals("Short question", store.titleFor("Short question"))
    }

    @Test
    fun titleFallsBackWhenThereIsNoUserText() {
        assertEquals(ConversationStore.NEW_CONVERSATION_TITLE, store.titleFor(""))
        assertEquals(ConversationStore.NEW_CONVERSATION_TITLE, store.titleFor("   "))
    }

    @Test
    fun titleTruncationDoesNotSplitSurrogatePairs() {
        val emoji = "\uD83D\uDE00" // one code point, two UTF-16 units
        // Cutting at 48 units would leave a lone high surrogate; the title
        // ends on the last whole code point instead.
        val splitTitle = store.titleFor("a".repeat(47) + emoji + " tail")
        assertEquals("a".repeat(47), splitTitle)
        assertEquals(47, splitTitle.codePointCount(0, splitTitle.length))

        // An emoji that fits whole within the limit survives intact.
        val wholeTitle = store.titleFor("a".repeat(46) + emoji + " tail")
        assertTrue(wholeTitle.startsWith("a".repeat(46) + emoji))
        assertFalse(Character.isHighSurrogate(wholeTitle.last()))
    }

    @Test
    fun pinnedConversationsSortFirstAndUnpinRestoresOrder() = runTest {
        store.createConversation("old", "Old", now = 1_000)
        store.createConversation("new", "New", now = 5_000)

        store.setPinned("old", true)
        assertEquals(listOf("old", "new"), store.conversations().first().map { it.id })
        assertTrue(store.conversations().first().single { it.id == "old" }.pinned)

        store.setPinned("old", false)
        assertEquals(listOf("new", "old"), store.conversations().first().map { it.id })
        assertFalse(store.conversations().first().single { it.id == "old" }.pinned)
    }

    @Test
    fun renameReplacesTitleWithoutBumpingUpdatedAt() = runTest {
        store.createConversation("c1", "Old title", now = 1_000)
        store.createConversation("c2", "Newer", now = 5_000)

        store.renameConversation("c1", "Renamed")

        val renamed = store.conversations().first().single { it.id == "c1" }
        assertEquals("Renamed", renamed.title)
        assertEquals(1_000, renamed.updatedAt)
        assertEquals(listOf("c2", "c1"), store.conversations().first().map { it.id })
    }

    @Test
    fun versionRowsRoundTripAndAreCleanedUp() = runTest {
        store.createConversation("c1", "Title", now = 1_000)
        store.appendMessage(message("m1", "c1", "ASSISTANT", "answer two", 1_000))
        store.appendMessage(message("m2", "c1", "ASSISTANT", "other", 2_000))
        store.saveVersion("m1", "answer one")
        store.saveVersion("m1", "answer two")
        store.saveVersion("m2", "other")
        store.updateSelectedVersion("m1", 1)

        val versions = store.messageVersions("c1").first()
        assertEquals(
            listOf("answer one", "answer two", "other"),
            versions.map { it.content },
        )
        assertEquals(listOf("m1", "m1", "m2"), versions.map { it.messageId })

        // Truncation at m1 removes only m1's version rows...
        store.deleteMessagesFrom("m2", "c1")
        assertEquals(listOf("m1", "m1"), store.messageVersions("c1").first().map { it.messageId })

        // ...and deleting the conversation removes the rest.
        store.deleteConversation("c1")
        assertTrue(store.messageVersions("c1").first().isEmpty())
    }

}
