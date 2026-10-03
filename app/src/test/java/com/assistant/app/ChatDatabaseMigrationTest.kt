package com.assistant.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.local.AttachmentEntity
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.local.MessageVersionEntity
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.data.local.ReasoningSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration tests verifying sequential schema upgrades and data survival from
 * previous releases (v1 through v7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseMigrationTest {

    private lateinit var context: Context
    private val dbName = "test_migration_chat.db"
    private var database: ChatDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun upgradeFromV1ToV8_preservesExistingDataAndEnablesAllNewFields() = runTest {
        // Step 1: Create a real SQLite database with v1 schema and data
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `conversations` (" +
                            "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE TABLE `messages` (" +
                            "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                            "`content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        val v1Db = helper.writableDatabase

        // Insert legacy v1 rows
        v1Db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt) VALUES ('c1', 'Legacy Conversation', 1000, 1000)")
        v1Db.execSQL("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES ('m1', 'c1', 'USER', 'What is L0?', 1000)")
        v1Db.execSQL("INSERT INTO messages (id, conversationId, role, content, createdAt) VALUES ('m2', 'c1', 'ASSISTANT', 'L0 is an AI chat app.', 1001)")
        v1Db.close()

        // Step 2: Open with Room applying the entire sequential migration chain 1->2->3->4->5->6->7->8
        val roomDb = Room.databaseBuilder(context, ChatDatabase::class.java, dbName)
            .addMigrations(
                ChatDatabase.MIGRATION_1_2,
                ChatDatabase.MIGRATION_2_3,
                ChatDatabase.MIGRATION_3_4,
                ChatDatabase.MIGRATION_4_5,
                ChatDatabase.MIGRATION_5_6,
                ChatDatabase.MIGRATION_6_7,
                ChatDatabase.MIGRATION_7_8,
            )
            .build()
        database = roomDb

        // Step 3: Verify existing conversation and messages survived intact with correct defaults
        val conv = roomDb.conversationDao().byId("c1")
        assertNotNull(conv)
        assertEquals("c1", conv?.id)
        assertEquals("Legacy Conversation", conv?.title)
        assertEquals(1000L, conv?.createdAt)
        assertFalse("v2 pinned default must be false", conv?.pinned ?: true)
        assertFalse("v7 searchEnabled default must be false", conv?.searchEnabled ?: true)

        val messages = roomDb.messageDao().observeForConversation("c1").first()
        assertEquals(2, messages.size)

        val userMsg = messages[0]
        assertEquals("m1", userMsg.id)
        assertEquals("What is L0?", userMsg.content)
        assertEquals(0, userMsg.selectedVersion)
        assertNull(userMsg.followUps)
        assertEquals("", userMsg.reasoning)
        assertNull(userMsg.sources)

        val assistantMsg = messages[1]
        assertEquals("m2", assistantMsg.id)
        assertEquals("L0 is an AI chat app.", assistantMsg.content)
        assertEquals(0, assistantMsg.selectedVersion)
        assertNull(assistantMsg.followUps)
        assertEquals("", assistantMsg.reasoning)
        assertNull(assistantMsg.sources)

        // Step 4: Verify writing and reading all new features on the migrated database
        // Follow-up suggestions persistence (added in v2)
        roomDb.messageDao().updateFollowUps("m2", "Tell me more?\nHow does it work?")
        val updatedAssistant = roomDb.messageDao().observeForConversation("c1").first()[1]
        assertEquals("Tell me more?\nHow does it work?", updatedAssistant.followUps)

        // Pinned status (added in v2)
        roomDb.conversationDao().setPinned("c1", true)
        assertTrue(roomDb.conversationDao().byId("c1")?.pinned == true)

        // Search enabled toggle (added in v7)
        roomDb.conversationDao().setSearchEnabled("c1", true)
        assertTrue(roomDb.conversationDao().byId("c1")?.searchEnabled == true)

        // Message versions (added in v2)
        roomDb.messageDao().insertVersion(MessageVersionEntity(messageId = "m2", content = "Alternative answer"))
        val versions = roomDb.messageDao().observeVersionsForConversation("c1").first()
        assertEquals(1, versions.size)
        assertEquals("Alternative answer", versions.first().content)

        // Provider entity (added in v3)
        val providerId = roomDb.providerDao().insert(
            ProviderEntity(name = "Local Ollama", baseUrl = "http://localhost:11434/v1", model = "llama3", isActive = true)
        )
        val savedProvider = roomDb.providerDao().byId(providerId)
        assertNotNull(savedProvider)
        assertEquals("Local Ollama", savedProvider?.name)

        // Attachments (added in v4)
        val attachment = AttachmentEntity(
            id = "att1",
            messageId = "m1",
            conversationId = "c1",
            kind = "IMAGE",
            displayName = "photo.jpg",
            mime = "image/jpeg",
            path = "/data/photo.jpg",
            sizeBytes = 2048,
            createdAt = 1002,
        )
        roomDb.attachmentDao().insert(attachment)
        val savedAttachments = roomDb.attachmentDao().observeForConversation("c1").first()
        assertEquals(1, savedAttachments.size)
        assertEquals("photo.jpg", savedAttachments.first().displayName)
    }

    @Test
    fun upgradeFromV6ToV8_preservesExistingDataAndAddsSearchEnabledAndReasoningSupport() = runTest {
        // Build v6 database
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(6) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `conversations` (" +
                            "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                            "`pinned` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE TABLE `messages` (" +
                            "`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `role` TEXT NOT NULL, " +
                            "`content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "`selectedVersion` INTEGER NOT NULL DEFAULT 0, `followUps` TEXT, " +
                            "`reasoning` TEXT NOT NULL DEFAULT '', `sources` TEXT, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)",
                    )
                    db.execSQL(
                        "CREATE TABLE `message_versions` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` TEXT NOT NULL, `content` TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE `providers` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                            "`baseUrl` TEXT NOT NULL, `model` TEXT NOT NULL, `isActive` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE `attachments` (" +
                            "`id` TEXT NOT NULL, `messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                            "`kind` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                            "`path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_message_versions_messageId` ON `message_versions` (`messageId`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_attachments_messageId` ON `attachments` (`messageId`)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        val v6Db = helper.writableDatabase

        v6Db.execSQL("INSERT INTO conversations (id, title, createdAt, updatedAt, pinned) VALUES ('c6', 'V6 Conversation', 2000, 2000, 1)")
        v6Db.execSQL(
            "INSERT INTO messages (id, conversationId, role, content, createdAt, selectedVersion, followUps, reasoning, sources) " +
                "VALUES ('m6_1', 'c6', 'USER', 'Search test', 2000, 0, NULL, '', NULL)",
        )
        v6Db.execSQL(
            "INSERT INTO messages (id, conversationId, role, content, createdAt, selectedVersion, followUps, reasoning, sources) " +
                "VALUES ('m6_2', 'c6', 'ASSISTANT', 'V6 Answer', 2001, 0, 'Follow 1', 'Thought process', '[{\"title\":\"Src\",\"url\":\"http://src.com\"}]')",
        )
        v6Db.execSQL(
            "INSERT INTO providers (name, baseUrl, model, isActive) VALUES " +
                "('Gemini', 'https://generativelanguage.googleapis.com', 'gemini-2.5-flash', 1)",
        )
        v6Db.close()

        // Migrate from v6 to v8 with MIGRATION_6_7 then MIGRATION_7_8
        val roomDb = Room.databaseBuilder(context, ChatDatabase::class.java, dbName)
            .addMigrations(ChatDatabase.MIGRATION_6_7, ChatDatabase.MIGRATION_7_8)
            .build()
        database = roomDb

        val conv = roomDb.conversationDao().byId("c6")
        assertNotNull(conv)
        assertEquals("V6 Conversation", conv?.title)
        assertTrue("pinned state from v6 must be preserved", conv?.pinned == true)
        assertFalse("searchEnabled should default to false", conv?.searchEnabled == true)

        val messages = roomDb.messageDao().observeForConversation("c6").first()
        assertEquals(2, messages.size)
        val assistant = messages[1]
        assertEquals("V6 Answer", assistant.content)
        assertEquals("Follow 1", assistant.followUps)
        assertEquals("Thought process", assistant.reasoning)
        assertEquals("[{\"title\":\"Src\",\"url\":\"http://src.com\"}]", assistant.sources)

        // Can update searchEnabled in v7+
        roomDb.conversationDao().setSearchEnabled("c6", true)
        assertTrue(roomDb.conversationDao().byId("c6")?.searchEnabled == true)

        // The pre-existing provider gains the reasoning column, defaulting to unknown.
        val provider = roomDb.providerDao().active()
        assertNotNull(provider)
        assertEquals("Gemini", provider?.name)
        assertEquals(ReasoningSupport.UNSPECIFIED, provider?.reasoningSupport)
    }

}
