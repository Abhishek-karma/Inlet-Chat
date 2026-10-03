package com.assistant.app.data

import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.data.local.ProviderModelEntity
import com.assistant.app.data.local.ProviderWithActiveModel
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.SecureKeyStore
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The editable, non-secret fields of a provider configuration. */
data class ProviderDraft(
    val name: String = "",
    val baseUrl: String = "",
)

/** The editable fields of one saved model under a provider. */
data class ModelDraft(
    val id: Long = 0,
    val model: String = "",
    val isActive: Boolean = false,
)

/** The active provider together with its active model (null when it has none). */
data class ActiveSelection(val provider: ProviderEntity, val model: ProviderModelEntity?)

/**
 * Saved provider configurations: the per-provider API keys, the saved models
 * of each provider (one active at a time), the single-active-provider rule,
 * seeding of the pre-1.2 configuration, and validation. Nothing else touches
 * the provider tables or key store.
 *
 * Key semantics for [addProvider]/[updateProvider]: a non-blank `apiKey`
 * replaces the stored key; null or blank keeps it (and one must be stored for
 * the provider to be usable). The key belongs to the provider and is shared by
 * all of its models.
 */
class ProviderStore(
    private val db: ChatDatabase,
    private val appPreferences: AppPreferences,
    private val keyStore: SecureKeyStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    fun providers(): Flow<List<ProviderEntity>> = db.providerDao().observeAll()

    /** Every provider with the name of its active model, for the settings list. */
    fun providersWithModel(): Flow<List<ProviderWithActiveModel>> = db.providerDao().observeAllWithModel()

    /** The provider the chat uses, or null when none is usable. */
    fun activeProvider(): Flow<ProviderEntity?> = db.providerDao().observeActive()

    /**
     * The active provider together with its active model. Re-emits when the
     * active provider changes and when the active model of that provider
     * changes (add/edit/delete/switch).
     */
    fun activeSelection(): Flow<ActiveSelection?> = db.providerDao().observeActive().flatMapLatest { active ->
        if (active == null) {
            flowOf(null)
        } else {
            db.providerModelDao().observeForProvider(active.id)
                .map { models -> ActiveSelection(active, models.firstOrNull { it.isActive }) }
        }
    }

    suspend fun provider(id: Long): ProviderEntity? = db.providerDao().byId(id)

    suspend fun apiKey(id: Long): String? = withContext(ioDispatcher) { keyStore.apiKey(id) }

    fun models(providerId: Long): Flow<List<ProviderModelEntity>> =
        db.providerModelDao().observeForProvider(providerId)

    suspend fun modelsOf(providerId: Long): List<ProviderModelEntity> =
        db.providerModelDao().forProvider(providerId)

    suspend fun model(modelId: Long): ProviderModelEntity? = db.providerModelDao().byId(modelId)

    /** The active model of [providerId], or null when it has none. */
    suspend fun activeModel(providerId: Long): ProviderModelEntity? =
        db.providerModelDao().activeForProvider(providerId)

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
                    isActive = true,
                ),
            )
            if (legacy.model.isNotBlank()) {
                db.providerModelDao().insert(
                    ProviderModelEntity(providerId = id, model = legacy.model, isActive = true),
                )
            }
            keyStore.legacyApiKey()?.let { keyStore.setApiKey(id, it) }
        }
        appPreferences.clearLegacyProviderConfig()
        keyStore.deleteLegacyApiKey()
    }

    /** Validates [draft] plus its [models] for provider [id] (0 = new); user-facing problem or null. */
    suspend fun validate(id: Long, draft: ProviderDraft, models: List<ModelDraft>, enteredKey: String?): String? {
        if (draft.name.isBlank()) return ERROR_NAME_REQUIRED
        val normalizedUrl = if (draft.baseUrl.trim().equals("gemini", ignoreCase = true)) {
            "https://generativelanguage.googleapis.com"
        } else {
            draft.baseUrl.trim()
        }
        val uri = try {
            URI(normalizedUrl)
        } catch (_: URISyntaxException) {
            return ERROR_BASE_URL_INVALID
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
            return ERROR_BASE_URL_INVALID
        }
        if (scheme == "http" && !isPermittedCleartextHost(host)) {
            return ERROR_CLEARTEXT_NOT_PERMITTED
        }
        if (models.none { it.model.isNotBlank() }) return ERROR_MODEL_REQUIRED
        if (models.any { it.model.trim().isEmpty() }) return ERROR_MODEL_REQUIRED
        if (enteredKey.isNullOrBlank() && apiKey(id).isNullOrBlank()) return ERROR_API_KEY_REQUIRED
        return null
    }

    fun isPermittedCleartextHost(host: String): Boolean {
        val cleanHost = host.lowercase().trim().removePrefix("[").removeSuffix("]")
        if (cleanHost == "localhost" || cleanHost == "127.0.0.1" || cleanHost == "10.0.2.2" || cleanHost == "::1") {
            return true
        }
        if (cleanHost.startsWith("127.")) {
            return true
        }
        if (cleanHost.endsWith(".local") || cleanHost.endsWith(".lan") ||
            cleanHost.endsWith(".home") || cleanHost.endsWith(".internal")) {
            return true
        }
        return false
    }

    /**
     * Creates a provider with its saved [models]; the first model (or the one
     * flagged active) becomes active. The returned id is the provider's; the
     * first provider added becomes the active provider.
     */
    suspend fun addProvider(draft: ProviderDraft, apiKey: String?, models: List<ModelDraft>): Long {
        val normalizedUrl = normalizeBaseUrl(draft.baseUrl)
        val id = db.providerDao().insert(
            ProviderEntity(
                name = draft.name.trim(),
                baseUrl = normalizedUrl,
            ),
        )
        saveModels(id, models)
        if (!apiKey.isNullOrBlank()) {
            withContext(ioDispatcher) { keyStore.setApiKey(id, apiKey) }
        }
        if (db.providerDao().active() == null) {
            db.providerDao().setActive(id)
        }
        return id
    }

    /**
     * Replaces provider [id]'s fields and saved models and, when [apiKey] is
     * non-blank, its key. The key is written first so a rebuilt chat provider
     * already sees it. The API key is untouched by model edits.
     */
    suspend fun updateProvider(id: Long, draft: ProviderDraft, apiKey: String?, models: List<ModelDraft>) {
        val normalizedUrl = normalizeBaseUrl(draft.baseUrl)
        if (!apiKey.isNullOrBlank()) {
            withContext(ioDispatcher) { keyStore.setApiKey(id, apiKey) }
        }
        db.providerDao().update(id, draft.name.trim(), normalizedUrl)
        saveModels(id, models)
    }

    /**
     * Persists the full model set of [providerId]: existing rows are updated,
     * new ones inserted, removed ones deleted. At most one model is active;
     * when none is flagged, the first is made active.
     */
    private suspend fun saveModels(providerId: Long, models: List<ModelDraft>) {
        val dao = db.providerModelDao()
        val keepIds = models.filter { it.id != 0L }.map { it.id }.toSet()
        dao.forProvider(providerId).filter { it.id !in keepIds }.forEach { dao.delete(it.id) }
        models.forEach { draft ->
            val entity = ProviderModelEntity(
                providerId = providerId,
                model = draft.model.trim(),
            )
            if (draft.id == 0L) {
                dao.insert(entity.copy(isActive = draft.isActive))
            } else {
                dao.update(id = draft.id, model = entity.model)
                if (draft.isActive) dao.setActiveForProvider(providerId, draft.id)
            }
        }
        ensureActiveModel(providerId)
    }

    /** Guarantees the provider has an active model whenever it has any. */
    private suspend fun ensureActiveModel(providerId: Long) {
        val dao = db.providerModelDao()
        if (dao.activeForProvider(providerId) != null) return
        val first = dao.forProvider(providerId).firstOrNull() ?: return
        dao.setActiveForProvider(providerId, first.id)
    }

    /**
     * Deletes one saved model. The provider's API key is untouched. If the
     * active model is deleted, another saved model becomes active; when none
     * remain, the provider simply has no active model.
     */
    suspend fun deleteModel(providerId: Long, modelId: Long) {
        val dao = db.providerModelDao()
        val wasActive = dao.activeForProvider(providerId)?.id == modelId
        dao.delete(modelId)
        if (wasActive) {
            val next = dao.forProvider(providerId).firstOrNull()
            if (next != null) dao.setActiveForProvider(providerId, next.id) else dao.clearActive(providerId)
        }
    }

    /** Makes [modelId] the active model of its provider. */
    suspend fun setActiveModel(providerId: Long, modelId: Long) {
        db.providerModelDao().setActiveForProvider(providerId, modelId)
    }

    /**
     * Deletes provider [id], all of its saved models, and its key. If it was
     * active, the lowest remaining provider becomes active; with none left the
     * app returns to the setup state.
     */
    suspend fun deleteProvider(id: Long) {
        db.providerModelDao().deleteForProvider(id)
        db.providerDao().delete(id)
        withContext(ioDispatcher) { keyStore.setApiKey(id, null) }
        if (db.providerDao().active() == null) {
            db.providerDao().firstOtherThan(id)?.let { db.providerDao().setActive(it.id) }
        }
    }

    suspend fun setActive(id: Long) {
        db.providerDao().setActive(id)
    }

    private fun normalizeBaseUrl(raw: String): String =
        if (raw.trim().equals("gemini", ignoreCase = true)) {
            "https://generativelanguage.googleapis.com"
        } else {
            raw.trim()
        }

    companion object {
        // User-facing and single-locale; not in resources because validate()
        // is context-free. Each message names the field and what to do, so the
        // editor can show it directly under the form.
        const val ERROR_NAME_REQUIRED = "Give this provider a name, so you can tell it apart in the list."
        const val ERROR_BASE_URL_INVALID = "The base URL must start with http:// or https://, for example https://api.openai.com/v1."
        const val ERROR_CLEARTEXT_NOT_PERMITTED = "Cleartext HTTP is only permitted for local or private network endpoints (such as localhost, 10.0.2.2, or .local/.lan/.home/.internal domains). Use HTTPS for remote providers."
        const val ERROR_MODEL_REQUIRED = "Add at least one model, and give every model a name. It is sent to the provider exactly as typed."
        const val ERROR_API_KEY_REQUIRED = "Enter the API key for this provider. Leave the field empty only when you are keeping a key you already saved."

        private const val DEFAULT_NAME = "Provider"
    }
}