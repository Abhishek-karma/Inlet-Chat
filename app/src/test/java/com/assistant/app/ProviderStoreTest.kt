package com.assistant.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.ModelDraft
import com.assistant.app.data.ProviderDraft
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.InMemorySecureKeyStore
import kotlinx.coroutines.Dispatchers
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
 * Behavior contract for [ProviderStore]: CRUD, saved models per provider with
 * one active, the single-active-provider rule with fallback on delete, shared
 * per-provider keys, validation, and the one-time seeding of the pre-1.2
 * configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderStoreTest {

    private val db: ChatDatabase = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val keyStore = InMemorySecureKeyStore()
    private val preferences = AppPreferences(
        ApplicationProvider.getApplicationContext(),
        Dispatchers.Unconfined,
    )
    private val store = ProviderStore(db, preferences, keyStore, Dispatchers.Unconfined)

    @After
    fun tearDown() {
        db.close()
    }

    private val validDraft = ProviderDraft(
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
    )
    private val oneModel = listOf(ModelDraft(model = "gpt-4o-mini", isActive = true))

    private suspend fun addProvider(
        draft: ProviderDraft = validDraft,
        key: String? = "sk-1",
        models: List<ModelDraft> = oneModel,
    ): Long = store.addProvider(draft, key, models)

    @Test
    fun addProvidersKeysAreIsolatedAndFirstBecomesActive() = runTest {
        val first = addProvider(key = "sk-first")
        val second = addProvider(validDraft.copy(name = "Other"), "sk-second")

        assertEquals("sk-first", store.apiKey(first))
        assertEquals("sk-second", store.apiKey(second))
        val active = store.activeProvider().firstBounded()
        assertEquals(first, active?.id)
        assertFalse(store.providers().firstBounded().single { it.id == second }.isActive)
    }

    @Test
    fun updateProviderKeepsStoredKeyWhenEntryBlank() = runTest {
        val id = addProvider(key = "sk-original")

        store.updateProvider(id, validDraft.copy(name = "Renamed"), null, oneModel)
        assertEquals("sk-original", store.apiKey(id))
        assertEquals("Renamed", store.providers().firstBounded().single { it.id == id }.name)

        store.updateProvider(id, validDraft, "sk-replacement", oneModel)
        assertEquals("sk-replacement", store.apiKey(id))
    }

    @Test
    fun providerStoresManyModelsWithOneActive() = runTest {
        val id = addProvider(
            models = listOf(
                ModelDraft(model = "alpha-model", isActive = true),
                ModelDraft(model = "bar"),
                ModelDraft(model = "local-model-7b"),
            ),
        )

        val models = store.modelsOf(id)
        assertEquals(listOf("alpha-model", "bar", "local-model-7b"), models.map { it.model })
        assertEquals("alpha-model", store.activeModel(id)?.model)
    }

    @Test
    fun switchingActiveModelKeepsTheProviderAndItsKey() = runTest {
        val id = addProvider(
            models = listOf(ModelDraft(model = "first", isActive = true), ModelDraft(model = "second")),
        )
        val second = store.modelsOf(id).single { it.model == "second" }

        store.setActiveModel(id, second.id)

        assertEquals("second", store.activeModel(id)?.model)
        assertEquals(id, store.activeProvider().firstBounded()?.id)
        assertEquals("sk-1", store.apiKey(id))
    }

    @Test
    fun deletingActiveModelPromotesAnother() = runTest {
        val id = addProvider(
            models = listOf(ModelDraft(model = "first", isActive = true), ModelDraft(model = "second")),
        )
        val first = store.modelsOf(id).single { it.model == "first" }

        store.deleteModel(id, first.id)

        assertEquals("second", store.activeModel(id)?.model)
        // The provider and its key survive a model deletion.
        assertEquals("sk-1", store.apiKey(id))
    }

    @Test
    fun deletingTheLastModelLeavesTheProviderWithoutAnActiveModel() = runTest {
        val id = addProvider()
        val only = store.modelsOf(id).single()

        store.deleteModel(id, only.id)

        assertNull(store.activeModel(id))
        assertEquals("sk-1", store.apiKey(id))
    }

    @Test
    fun updatingModelsKeepsExactlyTheSavedSet() = runTest {
        val id = addProvider(
            models = listOf(ModelDraft(model = "keep", isActive = true), ModelDraft(model = "remove")),
        )
        val keep = store.modelsOf(id).single { it.model == "keep" }

        store.updateProvider(
            id,
            validDraft,
            null,
            listOf(
                ModelDraft(id = keep.id, model = "keep", isActive = true),
                ModelDraft(model = "added"),
            ),
        )

        assertEquals(listOf("keep", "added"), store.modelsOf(id).map { it.model })
        assertEquals("keep", store.activeModel(id)?.model)
    }

    @Test
    fun setActiveSwitchesTheActiveProvider() = runTest {
        val first = addProvider()
        val second = addProvider(validDraft.copy(name = "Second"), "sk-2")

        store.setActive(second)

        assertEquals(second, store.activeProvider().firstBounded()?.id)
        assertFalse(store.providers().firstBounded().single { it.id == first }.isActive)
        store.setActive(first)
        assertEquals(first, store.activeProvider().firstBounded()?.id)
    }

    @Test
    fun deletingActiveProviderFallsBackToAnother() = runTest {
        val first = addProvider()
        val second = addProvider(validDraft.copy(name = "Second"), "sk-2")
        store.setActive(second)

        store.deleteProvider(second)
        assertEquals(first, store.activeProvider().firstBounded()?.id)
        assertNull(store.apiKey(second))

        store.deleteProvider(first)
        assertNull(store.activeProvider().firstBounded())
        assertNull(store.apiKey(first))
    }

    @Test
    fun deletingProviderRemovesItsModels() = runTest {
        val id = addProvider(
            models = listOf(ModelDraft(model = "a", isActive = true), ModelDraft(model = "b")),
        )
        store.deleteProvider(id)
        assertTrue(store.modelsOf(id).isEmpty())
    }

    @Test
    fun validateEnforcesNameUrlModelAndKey() = runTest {
        assertEquals(
            ProviderStore.ERROR_NAME_REQUIRED,
            store.validate(0, validDraft.copy(name = " "), oneModel, "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_BASE_URL_INVALID,
            store.validate(0, validDraft.copy(baseUrl = "example.com"), oneModel, "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_MODEL_REQUIRED,
            store.validate(0, validDraft, emptyList(), "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_MODEL_REQUIRED,
            store.validate(0, validDraft, listOf(ModelDraft(model = " ")), "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_API_KEY_REQUIRED,
            store.validate(0, validDraft, oneModel, null),
        )
        assertNull(store.validate(0, validDraft, oneModel, "sk-new"))

        // Existing provider with a stored key: blank entry is fine.
        val id = addProvider(key = "sk-stored")
        assertNull(store.validate(id, validDraft, oneModel, null))
        store.deleteProvider(id)
        val noKey = addProvider(key = null)
        assertEquals(
            ProviderStore.ERROR_API_KEY_REQUIRED,
            store.validate(noKey, validDraft, oneModel, null),
        )
    }

    @Test
    fun validateEnforcesCleartextEndpointRestrictions() = runTest {
        // Public remote HTTP is rejected to prevent cleartext exposure
        assertEquals(
            ProviderStore.ERROR_CLEARTEXT_NOT_PERMITTED,
            store.validate(0, validDraft.copy(baseUrl = "http://api.openai.com/v1"), oneModel, "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_CLEARTEXT_NOT_PERMITTED,
            store.validate(0, validDraft.copy(baseUrl = "http://evil.com/v1"), oneModel, "sk"),
        )

        // Local and private network domains are allowed for self-hosted LLMs
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://localhost:11434/v1"), oneModel, "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://127.0.0.1:11434/v1"), oneModel, "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://10.0.2.2:11434/v1"), oneModel, "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://my-pc.local:11434/v1"), oneModel, "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://ollama.lan:11434/v1"), oneModel, "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://desktop.home:8080/v1"), oneModel, "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://cluster.internal:8000/v1"), oneModel, "sk"))

        // Standard HTTPS is allowed everywhere
        assertNull(store.validate(0, validDraft.copy(baseUrl = "https://api.openai.com/v1"), oneModel, "sk"))
    }

    @Test
    fun seedingMigratesLegacyConfigurationOnce() = runTest {
        keyStore.setLegacyApiKey("sk-legacy")
        preferences.installLegacyProviderConfig("Legacy", "https://legacy.example.com/v1", "legacy-model")

        store.ensureSeeded()

        val providers = store.providers().firstBounded()
        assertEquals(1, providers.size)
        val seeded = providers.single()
        assertEquals("Legacy", seeded.name)
        assertEquals("https://legacy.example.com/v1", seeded.baseUrl)
        assertTrue(seeded.isActive)
        // The legacy model became the provider's first, active saved model.
        val model = store.activeModel(seeded.id)
        assertEquals("legacy-model", model?.model)
        assertEquals("sk-legacy", store.apiKey(seeded.id))
        assertNull(keyStore.legacyApiKey())
        assertNull(preferences.legacyProviderConfig())

        // Idempotent: a second pass (and leftover legacy fields) changes nothing.
        preferences.installLegacyProviderConfig("Again", "https://x.com/v1", "m")
        store.ensureSeeded()
        assertEquals(1, store.providers().firstBounded().size)
        assertNull(preferences.legacyProviderConfig())
    }

    @Test
    fun seedingWithoutUsableLegacyConfigurationCreatesNothing() = runTest {
        store.ensureSeeded()
        assertEquals(0, store.providers().firstBounded().size)
    }

    @Test
    fun activeSelectionTracksProviderAndModelChanges() = runTest {
        val first = addProvider(models = listOf(ModelDraft(model = "m1", isActive = true), ModelDraft(model = "m2")))
        val second = addProvider(validDraft.copy(name = "Second"), "sk-2")
        assertEquals(first, store.activeSelection().firstBounded()?.provider?.id)

        val m2 = store.modelsOf(first).single { it.model == "m2" }
        store.setActiveModel(first, m2.id)
        assertEquals(m2.id, store.activeSelection().firstBounded()?.model?.id)

        store.setActive(second)
        assertEquals(second, store.activeSelection().firstBounded()?.provider?.id)
    }
}
