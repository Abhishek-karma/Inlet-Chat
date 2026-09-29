package com.assistant.app.data

import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.SecureKeyStore
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** The editable, non-secret fields of a provider configuration. */
data class ProviderDraft(
    val name: String = "",
    val baseUrl: String = "",
    val model: String = "",
)

/**
 * Saved provider configurations: the per-provider API keys, the
 * single-active-provider rule, seeding of the pre-1.2 configuration, and
 * validation. Nothing else touches the provider table or key store.
 *
 * Key semantics for [addProvider]/[updateProvider]: a non-blank `apiKey`
 * replaces the stored key; null or blank keeps it (and one must be stored for
 * the provider to be usable).
 */
class ProviderStore(
    private val db: ChatDatabase,
    private val appPreferences: AppPreferences,
    private val keyStore: SecureKeyStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun providers(): Flow<List<ProviderEntity>> = db.providerDao().observeAll()

    /** The provider the chat uses, or null when none is usable. */
    fun activeProvider(): Flow<ProviderEntity?> = db.providerDao().observeActive()

    suspend fun provider(id: Long): ProviderEntity? = db.providerDao().byId(id)

    suspend fun apiKey(id: Long): String? = withContext(ioDispatcher) { keyStore.apiKey(id) }

    /**
     * Migrates the pre-1.2 single provider configuration into the store, once.
     * Idempotent. The steps are ordered so a crash can at worst leave the
     * legacy fields behind, never a half-seeded provider: the row is inserted
     * only while the table is empty, the key is copied right after, and the
     * legacy fields are cleared last.
     */
    suspend fun ensureSeeded() = withContext(ioDispatcher) {
        val legacy = appPreferences.legacyProviderConfig()
        if (legacy == null && keyStore.legacyApiKey() == null) return@withContext
        val dao = db.providerDao()
        if (legacy != null && dao.count() == 0) {
            val id = dao.insert(
                ProviderEntity(
                    name = legacy.name.ifBlank { DEFAULT_NAME },
                    baseUrl = legacy.baseUrl,
                    model = legacy.model,
                    isActive = true,
                ),
            )
            keyStore.legacyApiKey()?.let { keyStore.setApiKey(id, it) }
        }
        appPreferences.clearLegacyProviderConfig()
        keyStore.deleteLegacyApiKey()
    }

    /** Validates [draft] for provider [id] (0 = new); user-facing problem or null. */
    suspend fun validate(id: Long, draft: ProviderDraft, enteredKey: String?): String? {
        if (draft.name.isBlank()) return ERROR_NAME_REQUIRED
        if (!isHttpUrl(draft.baseUrl)) return ERROR_BASE_URL_INVALID
        if (draft.model.isBlank()) return ERROR_MODEL_REQUIRED
        if (enteredKey.isNullOrBlank() && apiKey(id).isNullOrBlank()) return ERROR_API_KEY_REQUIRED
        return null
    }

    /** Returns the new provider's id. The first provider added becomes active. */
    suspend fun addProvider(draft: ProviderDraft, apiKey: String?): Long {
        val id = db.providerDao().insert(
            ProviderEntity(
                name = draft.name.trim(),
                baseUrl = draft.baseUrl.trim(),
                model = draft.model.trim(),
            ),
        )
        if (!apiKey.isNullOrBlank()) {
            withContext(ioDispatcher) { keyStore.setApiKey(id, apiKey) }
        }
        if (db.providerDao().active() == null) {
            db.providerDao().setActive(id)
        }
        return id
    }

    /**
     * Replaces provider [id]'s fields and, when [apiKey] is non-blank, its
     * key. The key is written first so a rebuilt chat provider already sees it.
     */
    suspend fun updateProvider(id: Long, draft: ProviderDraft, apiKey: String?) {
        if (!apiKey.isNullOrBlank()) {
            withContext(ioDispatcher) { keyStore.setApiKey(id, apiKey) }
        }
        db.providerDao().update(id, draft.name.trim(), draft.baseUrl.trim(), draft.model.trim())
    }

    /**
     * Deletes provider [id] and its key. If it was active, the lowest remaining
     * id becomes active; with none left the app returns to the setup state.
     */
    suspend fun deleteProvider(id: Long) {
        db.providerDao().delete(id)
        withContext(ioDispatcher) { keyStore.setApiKey(id, null) }
        if (db.providerDao().active() == null) {
            db.providerDao().firstOtherThan(id)?.let { db.providerDao().setActive(it.id) }
        }
    }

    suspend fun setActive(id: Long) {
        db.providerDao().setActive(id)
    }

    private fun isHttpUrl(raw: String): Boolean {
        val uri = try {
            URI(raw.trim())
        } catch (_: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase()
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    companion object {
        // User-facing and single-locale; not in resources because validate()
        // is context-free. Each message names the field and what to do, so the
        // editor can show it directly under the form.
        const val ERROR_NAME_REQUIRED = "Give this provider a name, so you can tell it apart in the list."
        const val ERROR_BASE_URL_INVALID = "The base URL must start with http:// or https://, for example https://api.openai.com/v1."
        const val ERROR_MODEL_REQUIRED = "Enter a model name. It is sent to the provider exactly as typed."
        const val ERROR_API_KEY_REQUIRED = "Enter the API key for this provider. Leave the field empty only when you are keeping a key you already saved."

        private const val DEFAULT_NAME = "Provider"
    }
}
