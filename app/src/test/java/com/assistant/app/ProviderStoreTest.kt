package com.assistant.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.ProviderDraft
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.local.ReasoningSupport
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
 * Behavior contract for [ProviderStore]: CRUD, the single-active-provider rule
 * with fallback on delete, per-provider key isolation, validation, and the
 * one-time seeding of the pre-1.2 configuration.
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
        model = "gpt-4o-mini",
    )

    @Test
    fun addProvidersKeysAreIsolatedAndFirstBecomesActive() = runTest {
        val first = store.addProvider(validDraft, "sk-first")
        val second = store.addProvider(validDraft.copy(name = "Other"), "sk-second")

        assertEquals("sk-first", store.apiKey(first))
        assertEquals("sk-second", store.apiKey(second))
        val active = store.activeProvider().firstBounded()
        assertEquals(first, active?.id)
        assertFalse(store.providers().firstBounded().single { it.id == second }.isActive)
    }

    @Test
    fun updateProviderKeepsStoredKeyWhenEntryBlank() = runTest {
        val id = store.addProvider(validDraft, "sk-original")

        store.updateProvider(id, validDraft.copy(name = "Renamed"), null)
        assertEquals("sk-original", store.apiKey(id))
        assertEquals("Renamed", store.providers().firstBounded().single { it.id == id }.name)

        store.updateProvider(id, validDraft, "sk-replacement")
        assertEquals("sk-replacement", store.apiKey(id))
    }

    @Test
    fun reasoningSupportDefaultsToUnspecifiedAndRoundTripsWhenUpdated() = runTest {
        val id = store.addProvider(validDraft, "sk-1")

        // An added provider carries no declared reasoning support: unknown.
        assertEquals(
            ReasoningSupport.UNSPECIFIED,
            store.providers().firstBounded().single { it.id == id }.reasoningSupport,
        )

        store.updateProvider(id, validDraft.copy(reasoningSupport = ReasoningSupport.EFFORT), null)
        assertEquals(
            ReasoningSupport.EFFORT,
            store.providers().firstBounded().single { it.id == id }.reasoningSupport,
        )

        store.updateProvider(id, validDraft.copy(reasoningSupport = ReasoningSupport.BUDGET), null)
        assertEquals(
            ReasoningSupport.BUDGET,
            store.providers().firstBounded().single { it.id == id }.reasoningSupport,
        )
    }

    @Test
    fun setActiveSwitchesTheActiveProvider() = runTest {
        val first = store.addProvider(validDraft, "sk-1")
        val second = store.addProvider(validDraft.copy(name = "Second"), "sk-2")

        store.setActive(second)

        assertEquals(second, store.activeProvider().firstBounded()?.id)
        assertFalse(store.providers().firstBounded().single { it.id == first }.isActive)
        store.setActive(first)
        assertEquals(first, store.activeProvider().firstBounded()?.id)
    }

    @Test
    fun deletingActiveProviderFallsBackToAnother() = runTest {
        val first = store.addProvider(validDraft, "sk-1")
        val second = store.addProvider(validDraft.copy(name = "Second"), "sk-2")
        store.setActive(second)

        store.deleteProvider(second)
        assertEquals(first, store.activeProvider().firstBounded()?.id)
        assertNull(store.apiKey(second))

        store.deleteProvider(first)
        assertNull(store.activeProvider().firstBounded())
        assertNull(store.apiKey(first))
    }

    @Test
    fun validateEnforcesNameUrlModelAndKey() = runTest {
        assertEquals(
            ProviderStore.ERROR_NAME_REQUIRED,
            store.validate(0, validDraft.copy(name = " "), "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_BASE_URL_INVALID,
            store.validate(0, validDraft.copy(baseUrl = "example.com"), "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_MODEL_REQUIRED,
            store.validate(0, validDraft.copy(model = ""), "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_API_KEY_REQUIRED,
            store.validate(0, validDraft, null),
        )
        assertNull(store.validate(0, validDraft, "sk-new"))

        // Existing provider with a stored key: blank entry is fine.
        val id = store.addProvider(validDraft, "sk-stored")
        assertNull(store.validate(id, validDraft, null))
        // And with no stored key it is still required.
        store.updateProvider(id, validDraft, null)
        store.deleteProvider(id)
        val noKey = store.addProvider(validDraft, null)
        assertEquals(
            ProviderStore.ERROR_API_KEY_REQUIRED,
            store.validate(noKey, validDraft, null),
        )
    }

    @Test
    fun validateEnforcesCleartextEndpointRestrictions() = runTest {
        // Public remote HTTP is rejected to prevent cleartext exposure
        assertEquals(
            ProviderStore.ERROR_CLEARTEXT_NOT_PERMITTED,
            store.validate(0, validDraft.copy(baseUrl = "http://api.openai.com/v1"), "sk"),
        )
        assertEquals(
            ProviderStore.ERROR_CLEARTEXT_NOT_PERMITTED,
            store.validate(0, validDraft.copy(baseUrl = "http://evil.com/v1"), "sk"),
        )

        // Local and private network domains are allowed for self-hosted LLMs
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://localhost:11434/v1"), "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://127.0.0.1:11434/v1"), "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://10.0.2.2:11434/v1"), "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://my-pc.local:11434/v1"), "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://ollama.lan:11434/v1"), "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://desktop.home:8080/v1"), "sk"))
        assertNull(store.validate(0, validDraft.copy(baseUrl = "http://cluster.internal:8000/v1"), "sk"))

        // Standard HTTPS is allowed everywhere
        assertNull(store.validate(0, validDraft.copy(baseUrl = "https://api.openai.com/v1"), "sk"))
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
        assertEquals("legacy-model", seeded.model)
        assertTrue(seeded.isActive)
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

        preferences.installLegacyProviderConfig(name = "", baseUrl = "", model = "")
        keyStore.setLegacyApiKey("sk-unused")
        store.ensureSeeded()
        assertEquals(0, store.providers().firstBounded().size)
        assertNull(keyStore.legacyApiKey())
    }

    @Test
    fun seedingSkipsWhenProvidersAlreadyExist() = runTest {
        store.addProvider(validDraft, "sk-1")
        keyStore.setLegacyApiKey("sk-legacy")
        preferences.installLegacyProviderConfig("Legacy", "https://legacy.example.com/v1", "legacy-model")

        store.ensureSeeded()

        // The existing provider is untouched, no "Legacy" row was created, and
        // the legacy fields are cleared.
        assertEquals(1, store.providers().firstBounded().size)
        assertEquals("OpenAI", store.providers().firstBounded().single().name)
        assertNull(preferences.legacyProviderConfig())
    }
}
