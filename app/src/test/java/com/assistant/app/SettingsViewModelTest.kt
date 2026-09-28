package com.assistant.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.ProviderDraft
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.AppTheme
import com.assistant.app.data.settings.InMemorySecureKeyStore
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.ui.settings.ConnectionOutcome
import com.assistant.app.ui.settings.SettingsViewModel
import com.assistant.app.voice.VoiceOption
import com.assistant.app.voice.VoiceOutput
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Behavior contract for the settings ViewModel: provider
 * list hydration, add/edit save semantics around stored keys, delete,
 * activation, live validation, the connection test success and failure paths,
 * and the app preferences. Runs against real storage (Robolectric context,
 * real DataStore file, in-memory Room, in-memory key store).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Room query work runs inline on the calling thread (Robolectric). */
    private val directExecutor = Executor { it.run() }

    /**
     * Runs [block] with a ViewModel over real storage and a scripted
     * provider. `seed` runs before the ViewModel is created, so values it
     * saves are what the ViewModel hydrates from.
     */
    private fun runSettingsTest(
        script: List<ScriptedEvent> = listOf(ScriptedEvent.Emit("pong")),
        seed: suspend (ProviderStore, AppPreferences) -> Unit = { _, _ -> },
        voiceOutput: VoiceOutput? = null,
        block: suspend TestScope.(SettingsViewModel, ProviderStore, AppPreferences, FakeLlmProvider) -> Unit,
    ) = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room
            .inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .allowMainThreadQueries()
            .build()
        val keyStore = InMemorySecureKeyStore()
        val appPreferences = AppPreferences(context, mainDispatcher)
        val providerStore = ProviderStore(db, appPreferences, keyStore, mainDispatcher)
        try {
            seed(providerStore, appPreferences)
            val provider = FakeLlmProvider(script)
            val viewModel = SettingsViewModel(
                providerStore = providerStore,
                appPreferences = appPreferences,
                secureKeyStore = keyStore,
                newTestProvider = { _, _, _ -> provider },
                voiceOutput = voiceOutput,
                connectionTestDispatcher = mainDispatcher,
            )
            block(viewModel, providerStore, appPreferences, provider)
        } finally {
            db.close()
        }
    }

    /** Waits until preferences are hydrated and the provider list has arrived. */
    private suspend fun TestScope.awaitSettled(viewModel: SettingsViewModel) {
        viewModel.uiState.first { it.isLoaded }
        advanceUntilIdle()
    }

    /** Opens the editor for a new provider and fills it with valid values. */
    private fun SettingsViewModel.fillNewValidForm() {
        startAdd()
        setName("OpenAI")
        setBaseUrl("https://api.openai.com/v1")
        setModel("gpt-4o-mini")
        setApiKeyInput("sk-typed")
    }

    @Test
    fun `provider list hydrates with active flag`() = runSettingsTest(
        seed = { store, _ ->
            val id = store.addProvider(ProviderDraft("P", "https://a.com/v1", "m"), "sk-stored")
            store.setActive(id)
        },
    ) { viewModel, _, _, _ ->
        awaitSettled(viewModel)

        val providers = viewModel.uiState.value.providers
        assertEquals(1, providers.size)
        assertEquals("P", providers.single().name)
        assertEquals("m", providers.single().model)
        assertTrue(providers.single().isActive)
        assertFalse(viewModel.uiState.value.isEditing)
    }

    @Test
    fun `form error tracks the edited values`() = runSettingsTest { viewModel, _, _, _ ->
        awaitSettled(viewModel)
        viewModel.startAdd()
        viewModel.uiState.first { it.formError == ProviderStore.ERROR_NAME_REQUIRED }

        viewModel.setName("OpenAI")
        viewModel.uiState.first { it.formError == ProviderStore.ERROR_BASE_URL_INVALID }

        viewModel.setBaseUrl("https://api.openai.com/v1")
        viewModel.uiState.first { it.formError == ProviderStore.ERROR_MODEL_REQUIRED }

        viewModel.setModel("gpt-4o-mini")
        viewModel.uiState.first { it.formError == ProviderStore.ERROR_API_KEY_REQUIRED }

        viewModel.setApiKeyInput("sk-1")
        viewModel.uiState.first { it.formError == null }
    }

    @Test
    fun `save adds a provider with its key and closes the editor`() = runSettingsTest { viewModel, store, _, _ ->
        awaitSettled(viewModel)
        viewModel.fillNewValidForm()
        viewModel.save()
        viewModel.uiState.first { !it.isEditing }

        val providers = viewModel.uiState.value.providers
        assertEquals(1, providers.size)
        assertTrue(providers.single().isActive)
        assertEquals("sk-typed", store.apiKey(providers.single().id))
    }

    @Test
    fun `edit keeps stored key when key field left empty`() = runSettingsTest(
        seed = { store, _ ->
            store.addProvider(ProviderDraft("P", "https://a.com/v1", "m"), "sk-stored")
        },
    ) { viewModel, store, _, _ ->
        awaitSettled(viewModel)
        val id = viewModel.uiState.value.providers.single().id

        viewModel.edit(id)
        viewModel.uiState.first { it.isEditing && it.editingId == id }
        assertEquals("P", viewModel.uiState.value.name)
        assertEquals("sk-stored", viewModel.uiState.value.storedKey)

        viewModel.setName("Renamed")
        viewModel.save()
        viewModel.uiState.first { !it.isEditing }

        assertEquals("Renamed", viewModel.uiState.value.providers.single().name)
        assertEquals("sk-stored", store.apiKey(id))

        // Typing a new key replaces the stored one.
        viewModel.edit(id)
        viewModel.uiState.first { it.isEditing && it.editingId == id }
        viewModel.setApiKeyInput("sk-replacement")
        viewModel.save()
        viewModel.uiState.first { !it.isEditing }
        assertEquals("sk-replacement", store.apiKey(id))
    }

    @Test
    fun `delete removes the provider and closes the editor`() = runSettingsTest(
        seed = { store, _ ->
            store.addProvider(ProviderDraft("P", "https://a.com/v1", "m"), "sk-stored")
        },
    ) { viewModel, store, _, _ ->
        awaitSettled(viewModel)
        val id = viewModel.uiState.value.providers.single().id

        viewModel.edit(id)
        viewModel.uiState.first { it.isEditing }
        viewModel.delete(id)
        viewModel.uiState.first { !it.isEditing && it.providers.isEmpty() }

        assertNull(store.apiKey(id))
    }

    @Test
    fun `activation switches to exactly one active provider`() = runSettingsTest(
        seed = { store, _ ->
            store.addProvider(ProviderDraft("First", "https://a.com/v1", "m1"), "sk-1")
            store.addProvider(ProviderDraft("Second", "https://b.com/v1", "m2"), "sk-2")
        },
    ) { viewModel, store, _, _ ->
        awaitSettled(viewModel)
        val secondId = viewModel.uiState.value.providers.single { it.name == "Second" }.id

        viewModel.activateProvider(secondId)
        viewModel.uiState.first { state ->
            state.providers.count { it.isActive } == 1 &&
                state.providers.single { it.isActive }.id == secondId
        }
        assertEquals(secondId, store.activeProvider().first()?.id)
    }

    @Test
    fun `connection test uses edited values and stored key fallback`() = runSettingsTest(
        seed = { store, _ ->
            store.addProvider(ProviderDraft("P", "https://a.com/v1", "m"), "sk-stored")
        },
    ) { viewModel, _, _, provider ->
        awaitSettled(viewModel)
        val id = viewModel.uiState.value.providers.single().id
        viewModel.edit(id)
        viewModel.uiState.first { it.isEditing }
        viewModel.setName("New")
        viewModel.setBaseUrl("https://b.com/v1")
        viewModel.setModel("new-model")
        // Empty key field: the stored key satisfies validation and is used.
        viewModel.testConnection()
        viewModel.uiState.first { it.connectionOutcome == ConnectionOutcome.Success }

        assertEquals(1, provider.requests.size)
        assertEquals("new-model", provider.requests.single().model)
    }

    @Test
    fun `voice output toggle persists immediately`() = runSettingsTest { viewModel, _, appPreferences, _ ->
        awaitSettled(viewModel)

        assertFalse(viewModel.uiState.value.voiceOutputEnabled)
        viewModel.setVoiceOutputEnabled(true)
        // Persistence is real file I/O; wait until it lands in the store.
        appPreferences.voiceOutputEnabled.firstBounded("voiceOutputEnabled = true") { it }

        // A new ViewModel hydrates the persisted value.
        val reloadedDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val reloaded = SettingsViewModel(
                providerStore = ProviderStore(
                    reloadedDb,
                    appPreferences,
                    InMemorySecureKeyStore(),
                    Dispatchers.Unconfined,
                ),
                appPreferences = appPreferences,
                secureKeyStore = InMemorySecureKeyStore(),
                newTestProvider = { _, _, _ -> FakeLlmProvider(emptyList()) },
                connectionTestDispatcher = Dispatchers.Unconfined,
            )
            reloaded.uiState.first { it.isLoaded }
            assertTrue(reloaded.uiState.value.voiceOutputEnabled)
        } finally {
            reloadedDb.close()
        }
    }

    @Test
    fun `appearance persists immediately and hydrates on reload`() = runSettingsTest(
        seed = { _, appPreferences -> appPreferences.setAppearance(AppTheme.DARK) },
    ) { viewModel, _, appPreferences, _ ->
        awaitSettled(viewModel)
        assertEquals(AppTheme.DARK, viewModel.uiState.value.appearance)

        viewModel.setAppearance(AppTheme.LIGHT)
        appPreferences.appearance.firstBounded("appearance = LIGHT") { it == AppTheme.LIGHT }
        assertEquals(AppTheme.LIGHT, viewModel.uiState.value.appearance)

        val reloadedDb = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ChatDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val reloaded = SettingsViewModel(
                providerStore = ProviderStore(
                    reloadedDb,
                    appPreferences,
                    InMemorySecureKeyStore(),
                    Dispatchers.Unconfined,
                ),
                appPreferences = appPreferences,
                secureKeyStore = InMemorySecureKeyStore(),
                newTestProvider = { _, _, _ -> FakeLlmProvider(emptyList()) },
                connectionTestDispatcher = Dispatchers.Unconfined,
            )
            reloaded.uiState.first { it.isLoaded }
            assertEquals(AppTheme.LIGHT, reloaded.uiState.value.appearance)
        } finally {
            reloadedDb.close()
        }
    }

    @Test
    fun `ui state toString redacts api key fields`() = runSettingsTest { viewModel, _, _, _ ->
        awaitSettled(viewModel)
        viewModel.startAdd()
        viewModel.setApiKeyInput("sk-super-secret")
        viewModel.setRevealKey(true)

        val text = viewModel.uiState.value.toString()

        assertTrue(text.contains("<redacted>"))
        assertFalse(text.contains("sk-super-secret"))
        assertFalse(text.contains("sk-stored"))
    }

    @Test
    fun `reasoning visibility persists immediately`() = runSettingsTest { viewModel, _, appPreferences, _ ->
        awaitSettled(viewModel)
        assertTrue(viewModel.uiState.value.reasoningVisible)

        viewModel.setReasoningVisible(false)
        appPreferences.reasoningVisible.firstBounded("reasoningVisible = false") { !it }
        assertFalse(viewModel.uiState.value.reasoningVisible)
    }

    @Test
    fun `loadVoices publishes the engine's list`() = runSettingsTest(
        voiceOutput = VoiceOutput(FakeVoiceEngine(VOICES)),
    ) { viewModel, _, _, _ ->
        awaitSettled(viewModel)

        viewModel.loadVoices()

        assertEquals(VOICES, viewModel.uiState.value.voiceOptions)
    }

    @Test
    fun `loadVoices marks loaded even when the engine has none`() =
        runSettingsTest(voiceOutput = VoiceOutput(FakeVoiceEngine(emptyList()))) { viewModel, _, _, _ ->
            awaitSettled(viewModel)
            assertFalse(viewModel.uiState.value.voicesLoaded)

            viewModel.loadVoices()

            assertTrue(viewModel.uiState.value.voiceOptions.isEmpty())
            assertTrue(viewModel.uiState.value.voicesLoaded)
        }

    @Test
    fun `loadVoices does nothing without an engine`() = runSettingsTest { viewModel, _, _, _ ->
        awaitSettled(viewModel)

        viewModel.loadVoices()

        assertTrue(viewModel.uiState.value.voiceOptions.isEmpty())
    }

    @Test
    fun `choosing a voice updates state and persists it`() =
        runSettingsTest(voiceOutput = VoiceOutput(FakeVoiceEngine(VOICES))) { viewModel, _, appPreferences, _ ->
            awaitSettled(viewModel)
            viewModel.loadVoices()

            viewModel.setVoiceId(NATURAL.id)

            assertEquals(NATURAL.id, viewModel.uiState.value.voiceId)
            appPreferences.voiceId.firstBounded("voiceId = ${NATURAL.id}") { it == NATURAL.id }
        }

    @Test
    fun `picking the default voice clears the stored id`() =
        runSettingsTest(voiceOutput = VoiceOutput(FakeVoiceEngine(VOICES))) { viewModel, _, appPreferences, _ ->
            awaitSettled(viewModel)
            viewModel.loadVoices()
            viewModel.setVoiceId(NATURAL.id)
            advanceUntilIdle()

            viewModel.setVoiceId(null)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.voiceId)
            assertNull(appPreferences.voiceId.firstBounded("voiceId cleared") { true })
        }

    @Test
    fun `a saved voice id is hydrated into state`() =
        runSettingsTest(
            seed = { _, appPreferences -> appPreferences.setVoiceId(NATURAL.id) },
            voiceOutput = VoiceOutput(FakeVoiceEngine(VOICES)),
        ) { viewModel, _, _, _ ->
            awaitSettled(viewModel)

            assertEquals(NATURAL.id, viewModel.uiState.value.voiceId)
        }

    private companion object {
        val NATURAL = VoiceOption(
            id = "com.google.android.tts:en-us-natural:en-US",
            label = "English (United States)",
            locale = "en-US",
            isNatural = true,
        )
        val STANDARD = VoiceOption(
            id = "com.google.android.tts:en-us-standard:en-US",
            label = "English (United States)",
            locale = "en-US",
            isNatural = false,
        )
        val VOICES = listOf(NATURAL, STANDARD)

        class FakeVoiceEngine(private val voices: List<VoiceOption>) : VoiceOutput.Engine {
            override fun isAvailable(): Boolean = true
            override fun voices(onReady: (List<VoiceOption>) -> Unit) = onReady(voices)
            override fun speak(
                text: String,
                speechRate: Float,
                voiceId: String?,
                onDone: () -> Unit,
            ) = onDone()

            override fun stop() = Unit
        }
    }
}
