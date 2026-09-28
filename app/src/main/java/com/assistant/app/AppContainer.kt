package com.assistant.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.assistant.app.data.AttachmentIngester
import com.assistant.app.data.FollowUpSuggestions
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ConversationStore
import com.assistant.app.data.ProviderDraft
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.AppTheme
import com.assistant.app.data.settings.TextSize
import com.assistant.app.data.settings.EncryptedSecureKeyStore
import com.assistant.app.data.settings.SecureKeyStore
import com.assistant.app.llm.OpenAICompatibleProvider
import com.assistant.app.llm.WebSearchClient
import com.assistant.app.ui.chat.ChatViewModel
import com.assistant.app.ui.settings.SettingsViewModel
import com.assistant.app.voice.AndroidVoiceInput
import com.assistant.app.voice.AndroidVoiceOutput
import com.assistant.app.voice.VoiceInput
import com.assistant.app.voice.VoiceOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds the app's lazily-created singletons. No DI framework: the container is
 * built once in [AssistantApp] and screens receive what they need as plain
 * parameters.
 */
class AppContainer(context: Context) {

    /**
 * Application preferences (voice output, appearance) and API key storage
 * (encrypted preferences). Created lazily — the Application base context
 * is not attached during this container's construction — and neither does
 * any I/O until first use.
     */
    val appPreferences: AppPreferences by lazy { AppPreferences(context) }
    private val secureKeyStore: SecureKeyStore by lazy { EncryptedSecureKeyStore(context) }

    /**
 * The one shared HTTP client for every provider request:
 * connect 15s, read 60s, write 15s. The read timeout bounds the wait
 * between stream bytes, not the total stream duration.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Outlives everything it runs; the container lives for the whole process. */
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The single Room database for conversation and provider persistence. */
    private val chatDatabase: ChatDatabase by lazy {
        Room.databaseBuilder(context, ChatDatabase::class.java, "assistant.db")
            .addMigrations(
                ChatDatabase.MIGRATION_1_2,
                ChatDatabase.MIGRATION_2_3,
                ChatDatabase.MIGRATION_3_4,
                ChatDatabase.MIGRATION_4_5,
                ChatDatabase.MIGRATION_5_6,
                ChatDatabase.MIGRATION_6_7,
            )
            .build()
    }

    /** Conversation persistence layer over [chatDatabase]. */
    private val conversationStore: ConversationStore by lazy { ConversationStore(chatDatabase) }

    /**
 * Saved provider configurations: CRUD, the
 * single-active-provider rule, per-provider keys, and the one-time
 * seeding of the pre-1.2 configuration.
     */
    val providerStore: ProviderStore by lazy {
        ProviderStore(chatDatabase, appPreferences, secureKeyStore)
    }

    /**
 * Deletes attachment copies no row references (an app death between
 * ingest and send leaves orphans); best-effort cleanup at startup.
     */
    private suspend fun sweepOrphanAttachments() {
        val dir = attachmentIngester.attachmentsDir
        val referenced = chatDatabase.attachmentDao().allPaths().toHashSet()
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath !in referenced) {
                runCatching { file.delete() }
            }
        }
    }

    /**
 * The chat's generation wiring for the active provider, rebuilt whenever
 * the active provider or its configuration changes. Starts as
 * [ChatLlmState.Loading] until the legacy configuration has been seeded
 * and the active provider read once, and becomes [ChatLlmState.NeedsSetup]
 * when no usable provider exists (none saved, or no API key).
    
 * Configuration changes rarely, so rebuilding the cheap provider object
 * per change is fine — it is never recreated per request or token.
     */
    val chatLlm: StateFlow<ChatLlmState> by lazy {
        val state = MutableStateFlow<ChatLlmState>(ChatLlmState.Loading)
        watchScope.launch { sweepOrphanAttachments() }
        watchScope.launch {
            providerStore.ensureSeeded()
            providerStore.activeProvider().collect { active ->
                state.value = if (active == null) {
                    ChatLlmState.NeedsSetup
                } else {
                    val key = providerStore.apiKey(active.id)
                    val baseUrl = active.baseUrl.trim()
                    val model = active.model.trim()
                    if (baseUrl.isBlank() || model.isBlank() || key.isNullOrBlank()) {
                        ChatLlmState.NeedsSetup
                    } else {
                        ChatLlmState.Ready(
                            provider = OpenAICompatibleProvider(httpClient, baseUrl, key, model),
                            model = model,
                            providerId = active.id,
                            name = active.name,
                        )
                    }
                }
            }
        }
        state
    }

    /**
 * Single conversation core for this app run, persisted through
 * [conversationStore]. Attachments store their file copies under the
 * ingester's directory.
     */
    private val chatRepository: ChatRepository by lazy {
        ChatRepository(
            chatLlm,
            store = conversationStore,
            followUpSuggestions = { provider, model, question, answer ->
                FollowUpSuggestions.generate(provider, model, question, answer)
            },
            attachmentsDir = attachmentIngester.attachmentsDir,
            webSearch = { query ->
                val key = secureKeyStore.searchApiKey()
                if (key == null) null else WebSearchClient(httpClient, key).search(query)
            },
        )
    }

    /** Ingests picked and captured files into stored attachment copies. */
    private val attachmentIngester: AttachmentIngester by lazy { AttachmentIngester(context) }

    /**
 * Voice input/output, lazily built around the platform engines.
 * Both degrade to unavailable off-device (Robolectric), where the mic button
 * is hidden and voice output stays silent.
     */
    private val voiceInput by lazy { VoiceInput(AndroidVoiceInput(context)) }
    private val voiceOutput by lazy { VoiceOutput(AndroidVoiceOutput(context)) }

    /** Mirrors the persisted voice-output preference for the chat ViewModel. */
    val voiceOutputEnabled: StateFlow<Boolean> by lazy {
        MutableStateFlow(false).also { flow ->
            watchScope.launch {
                appPreferences.voiceOutputEnabled.collect { flow.value = it }
            }
        }
    }

    /** Mirrors the persisted appearance preference for the activity theme. */
    val appearance: StateFlow<AppTheme> by lazy {
        MutableStateFlow(AppTheme.SYSTEM).also { flow ->
            watchScope.launch {
                appPreferences.appearance.collect { flow.value = it }
            }
        }
    }

    /** Mirrors the persisted reasoning-visibility preference for the chat UI. */
    private val reasoningVisible: StateFlow<Boolean> by lazy {
        MutableStateFlow(true).also { flow ->
            watchScope.launch {
                appPreferences.reasoningVisible.collect { flow.value = it }
            }
        }
    }

    /** Mirrors the persisted voice preferences for the chat ViewModel. */
    private val voiceAutoPlay: StateFlow<Boolean> by lazy {
        MutableStateFlow(true).also { flow ->
            watchScope.launch {
                appPreferences.voiceAutoPlay.collect { flow.value = it }
            }
        }
    }

    private val voiceSpeed: StateFlow<Float> by lazy {
        MutableStateFlow(1.0f).also { flow ->
            watchScope.launch {
                appPreferences.voiceSpeed.collect { flow.value = it }
            }
        }
    }

    val voiceId: StateFlow<String?> by lazy {
        MutableStateFlow<String?>(null).also { flow ->
            watchScope.launch {
                appPreferences.voiceId.collect { flow.value = it }
            }
        }
    }

    /** Mirrors the persisted text-size preference for the activity. */
    val textSize: StateFlow<TextSize> by lazy {
        MutableStateFlow(TextSize.NORMAL).also { flow ->
            watchScope.launch {
                appPreferences.textSize.collect { flow.value = it }
            }
        }
    }

    /** Whether a web-search API key is stored. */
    private val searchAvailable: StateFlow<Boolean> by lazy {
        MutableStateFlow(false).also { flow ->
            watchScope.launch {
                appPreferences.searchConfigured.collect { flow.value = it }
            }
        }
    }

    /**
 * Factory for the chat ViewModel; the caller decides the store scope.
     */
    fun chatViewModelFactory(): ViewModelProvider.Factory =
        ChatViewModel.Factory(
            chatRepository,
            chatLlm,
            voiceInput,
            voiceOutput,
            { voiceOutputEnabled.value },
            providerStore.providers(),
            { id -> providerStore.setActive(id) },
            attachmentIngester,
            reasoningVisible,
            { voiceAutoPlay.value },
            { voiceSpeed.value },
            { voiceId.value },
            searchAvailable,
            voiceOutputEnabled,
            { enabled -> appPreferences.setVoiceOutputEnabled(enabled) },
        )

    /**
 * Factory for the settings ViewModel. The connection test builds a
 * temporary provider from the edited configuration — including a key that
 * has been entered but not saved yet.
     */
    fun settingsViewModelFactory(): ViewModelProvider.Factory =
        SettingsViewModel.Factory(
            providerStore,
            appPreferences,
            secureKeyStore,
            { baseUrl: String, model: String, apiKey: String ->
                OpenAICompatibleProvider(httpClient, baseUrl, apiKey, model)
            },
            ttsAvailable = voiceOutput.isAvailable,
            voiceOutput = voiceOutput,
        )
}
