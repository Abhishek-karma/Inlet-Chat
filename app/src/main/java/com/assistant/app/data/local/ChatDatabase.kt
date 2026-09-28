package com.assistant.app.data.local

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One row per conversation; [updatedAt] drives the history ordering and
 * [pinned] sorts conversations above unpinned ones.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Whether web search is enabled for this conversation. */
    @ColumnInfo(defaultValue = "0") val searchEnabled: Boolean = false,
)

/**
 * One row per user/assistant message. [role] is the [com.assistant.app.llm.model.Role]
 * name; system messages are never persisted. [content] is conversation text only —
 * provider credentials never enter message records.

 * [selectedVersion] indexes into the message's answer versions (rows in
 * [MessageVersionEntity]); [followUps] holds the suggested follow-up questions
 * for this answer, newline-separated, when any were generated.
 */
@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val selectedVersion: Int = 0,
    val followUps: String? = null,
    @ColumnInfo(defaultValue = "''") val reasoning: String = "",
    /** JSON-encoded web sources of this answer; null when none. */
    val sources: String? = null,
)

/** One completed answer of an assistant message; rowid order is answer order. */
@Entity(tableName = "message_versions", indices = [Index("messageId")])
data class MessageVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val content: String,
)

/**
 * One saved OpenAI-compatible provider configuration; its API key lives in
 * [com.assistant.app.data.settings.SecureKeyStore] under [ProviderEntity.id].
 * Exactly one row has [isActive] set — the provider the chat uses.
 */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val model: String,
    val isActive: Boolean = false,
)

@Dao
interface ProviderDao {

    @Query("SELECT * FROM providers ORDER BY id ASC")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ProviderEntity?>

    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int

    @Insert
    suspend fun insert(provider: ProviderEntity): Long

    @Query("UPDATE providers SET name = :name, baseUrl = :baseUrl, model = :model WHERE id = :id")
    suspend fun update(id: Long, name: String, baseUrl: String, model: String)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: Long)

    /** Marks [id] the single active provider. */
    @Query("UPDATE providers SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setActive(id: Long)

    @Query("SELECT * FROM providers WHERE isActive = 1 LIMIT 1")
    suspend fun active(): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id = :id")
    suspend fun byId(id: Long): ProviderEntity?

    @Query("SELECT * FROM providers WHERE id != :id ORDER BY id ASC LIMIT 1")
    suspend fun firstOtherThan(id: Long): ProviderEntity?
}

/**
 * One file attached to a user message. [kind] is the
 * [com.assistant.app.llm.model.UiAttachment.Kind] name; [path] is the
 * app-internal copy of the file that survives process death. Attachment rows
 * are deleted with their messages.
 */
@Entity(tableName = "attachments", indices = [Index("messageId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val conversationId: String,
    val kind: String,
    val displayName: String,
    val mime: String,
    val path: String,
    val sizeBytes: Long,
    val createdAt: Long,
)

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query(
        "UPDATE conversations SET updatedAt = :updatedAt " +
            "WHERE id = (SELECT conversationId FROM messages WHERE id = :messageId)",
    )
    suspend fun touchConversationOf(messageId: String, updatedAt: Long)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE conversations SET searchEnabled = :enabled WHERE id = :id")
    suspend fun setSearchEnabled(id: String, enabled: Boolean)

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT title FROM conversations WHERE id = :id")
    suspend fun titleOf(id: String): String?
}

@Dao
interface MessageDao {

    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAt ASC, rowid ASC",
    )
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET content = :content, reasoning = :reasoning WHERE id = :id")
    suspend fun updateContent(id: String, content: String, reasoning: String)

    @Query("UPDATE messages SET selectedVersion = :index WHERE id = :id")
    suspend fun updateSelectedVersion(id: String, index: Int)

    @Query("UPDATE messages SET followUps = :followUps WHERE id = :id")
    suspend fun updateFollowUps(id: String, followUps: String?)

    @Query("UPDATE messages SET sources = :sources WHERE id = :id")
    suspend fun updateSources(id: String, sources: String?)

    /** Answer versions of one conversation, insertion-ordered per message. */
    @Query(
        "SELECT message_versions.* FROM message_versions " +
            "JOIN messages ON messages.id = message_versions.messageId " +
            "WHERE messages.conversationId = :conversationId " +
            "ORDER BY message_versions.id ASC",
    )
    fun observeVersionsForConversation(conversationId: String): Flow<List<MessageVersionEntity>>

    @Insert
    suspend fun insertVersion(version: MessageVersionEntity)

    /**
 * Deletes [messageId] and every message inserted after it in
 * [conversationId] (rowid order = insert order, robust against equal
 * createdAt values). Used for edit/resend and regenerate truncation.
     */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId " +
            "AND rowid >= (SELECT rowid FROM messages WHERE id = :messageId)",
    )
    suspend fun deleteFrom(messageId: String, conversationId: String)

    /** Version rows of [messageId] and of every message inserted after it. */
    @Query(
        "DELETE FROM message_versions WHERE messageId IN (SELECT id FROM messages " +
            "WHERE conversationId = :conversationId " +
            "AND rowid >= (SELECT rowid FROM messages WHERE id = :messageId))",
    )
    suspend fun deleteVersionsFrom(messageId: String, conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query(
        "DELETE FROM message_versions WHERE messageId IN " +
            "(SELECT id FROM messages WHERE conversationId = :conversationId)",
    )
    suspend fun deleteVersionsForConversation(conversationId: String)
}

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE conversationId = :conversationId ORDER BY rowid ASC")
    fun observeForConversation(conversationId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT path FROM attachments WHERE conversationId = :conversationId")
    suspend fun pathsForConversation(conversationId: String): List<String>

    /** Attachment rows of [messageId] and of every message inserted after it. */
    @Query(
        "SELECT * FROM attachments WHERE messageId IN (SELECT id FROM messages " +
            "WHERE conversationId = :conversationId " +
            "AND rowid >= (SELECT rowid FROM messages WHERE id = :messageId))",
    )
    suspend fun forMessagesFrom(messageId: String, conversationId: String): List<AttachmentEntity>

    @Insert
    suspend fun insert(attachment: AttachmentEntity)

    @Query(
        "DELETE FROM attachments WHERE messageId IN (SELECT id FROM messages " +
            "WHERE conversationId = :conversationId " +
            "AND rowid >= (SELECT rowid FROM messages WHERE id = :messageId))",
    )
    suspend fun deleteFrom(messageId: String, conversationId: String)

    @Query("DELETE FROM attachments WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("SELECT path FROM attachments")
    suspend fun allPaths(): List<String>
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageVersionEntity::class,
        ProviderEntity::class,
        AttachmentEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun attachmentDao(): AttachmentDao

    companion object {
        /** v2: answer versions, follow-up suggestions, pinned conversations. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN selectedVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN followUps TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_versions` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`messageId` TEXT NOT NULL, `content` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_versions_messageId` " +
                        "ON `message_versions` (`messageId`)",
                )
            }
        }

        /**
         * v3: saved provider configurations. The table starts empty; the
         * pre-1.2 DataStore configuration seeds it once at app start (see
         * [com.assistant.app.data.ProviderStore.ensureSeeded]).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `providers` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`baseUrl` TEXT NOT NULL, `model` TEXT NOT NULL, `isActive` INTEGER NOT NULL)",
                )
            }
        }

        /** v4: per-message attachments. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachments` " +
                        "(`id` TEXT NOT NULL, `messageId` TEXT NOT NULL, `conversationId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `displayName` TEXT NOT NULL, `mime` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_attachments_messageId` " +
                        "ON `attachments` (`messageId`)",
                )
            }
        }

        /** v5: per-message model reasoning. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v6: web sources persisted under answers. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN sources TEXT")
            }
        }

        /** v7: per-conversation web-search toggle. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN searchEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
