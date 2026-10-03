package com.assistant.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.assistant.app.data.AttachmentIngester
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatRepository
import com.assistant.app.data.ConversationStore
import com.assistant.app.data.FollowUpSuggestions
import com.assistant.app.data.LlmProviderCoordinator
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.local.ChatDatabase
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.AppTheme
import com.assistant.app.data.settings.EncryptedSecureKeyStore
import com.assistant.app.data.settings.SecureKeyStore
import com.assistant.app.data.settings.TextSize
import com.assistant.app.data.update.AndroidUpdateNotifier
import com.assistant.app.data.update.GitHubUpdateChecker
import com.assistant.app.data.update.UpdateChecker
import com.assistant.app.data.update.UpdateManager
import com.assistant.app.data.update.UpdateNotifier
import com.assistant.app.llm.DuckDuckGoSearchProvider
import com.assistant.app.llm.HttpPageFetcher
import com.assistant.app.llm.JsoupContentExtractor
import com.assistant.app.llm.OpenAICompatibleProvider
import com.assistant.app.llm.WebSearchClient
import com.assistant.app.ui.chat.ChatViewModel
import com.assistant.app.ui.settings.SettingsViewModel
import com.assistant.app.voice.AndroidVoiceInput
import com.assistant.app.voice.AndroidVoiceOutput
import com.assistant.app.voice.VoiceInput
import com.assistant.app.voice.VoiceOutput
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Holds the app's lazily-created singletons and constructs dependencies.
 * No DI framework: dependencies are built once and passed to where they are needed.
 */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appPreferences: AppPreferences by lazy { AppPreferences(context) }
    private val secureKeyStore: SecureKeyStore by lazy { EncryptedSecureKeyStore(context) }

    val appearance: StateFlow<AppTheme> by lazy {
        appPreferences.appearance.stateIn(appScope, SharingStarted.Eagerly, AppTheme.SYSTEM)
    }

    val textSize: StateFlow<TextSize> by lazy {
        appPreferences.textSize.stateIn(appScope, SharingStarted.Eagerly, TextSize.NORMAL)
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

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

    private val conversationStore: ConversationStore by lazy {
        ConversationStore(chatDatabase)
    }

    val providerStore: ProviderStore by lazy {
        ProviderStore(chatDatabase, appPreferences, secureKeyStore)
    }

    private val llmProviderCoordinator: LlmProviderCoordinator by lazy {
        LlmProviderCoordinator(providerStore, httpClient, appScope)
    }

    val chatLlm: StateFlow<ChatLlmState> get() = llmProviderCoordinator.chatLlm

    val attachmentIngester: AttachmentIngester by lazy {
        AttachmentIngester(context).also { ingester ->
            appScope.launch {
                ingester.sweepOrphans { chatDatabase.attachmentDao().allPaths().toHashSet() }
            }
        }
    }

    private val chatRepository: ChatRepository by lazy {
        ChatRepository(
            chatLlm = chatLlm,
            store = conversationStore,
            followUpSuggestions = { provider, model, question, answer ->
                FollowUpSuggestions.generate(provider, model, question, answer)
            },
            loadThinkSelection = { model -> appPreferences.thinkSelection(model) },
            saveThinkSelection = { model, config -> appPreferences.setThinkSelection(model, config) },
            attachmentsDir = attachmentIngester.attachmentsDir,
            webSearch = { query ->
                val client = httpClient
                WebSearchClient(
                    provider = DuckDuckGoSearchProvider(client),
                    pageFetcher = HttpPageFetcher(client),
                    contentExtractor = JsoupContentExtractor(),
                ).search(query)
            },
        )
    }

    private val voiceInput: VoiceInput by lazy { VoiceInput(AndroidVoiceInput(context)) }
    private val voiceOutput: VoiceOutput by lazy { VoiceOutput(AndroidVoiceOutput(context)) }

    fun chatViewModelFactory(): ViewModelProvider.Factory {
        val voiceOutputState = appPreferences.voiceOutputEnabled.stateIn(appScope, SharingStarted.Eagerly, false)
        val voiceAutoPlayState = appPreferences.voiceAutoPlay.stateIn(appScope, SharingStarted.Eagerly, true)
        val voiceSpeedState = appPreferences.voiceSpeed.stateIn(appScope, SharingStarted.Eagerly, 1.0f)
        val voiceIdState = appPreferences.voiceId.stateIn(appScope, SharingStarted.Eagerly, null)
        val reasoningVisibleState = appPreferences.reasoningVisible.stateIn(appScope, SharingStarted.Eagerly, true)

        return ChatViewModel.Factory(
            repository = chatRepository,
            chatLlm = chatLlm,
            voiceInput = voiceInput,
            voiceOutput = voiceOutput,
            isVoiceOutputEnabled = { voiceOutputState.value },
            providers = providerStore.providers(),
            activateProviderById = { id -> providerStore.setActive(id) },
            attachmentIngester = attachmentIngester,
            reasoningVisible = reasoningVisibleState,
            voiceAutoPlay = { voiceAutoPlayState.value },
            voiceSpeed = { voiceSpeedState.value },
            voiceId = { voiceIdState.value },
            voiceOutputEnabled = voiceOutputState,
            setVoiceOutput = { enabled -> appPreferences.setVoiceOutputEnabled(enabled) },
        )
    }

    val updateChecker: UpdateChecker by lazy {
        GitHubUpdateChecker(httpClient)
    }

    val updateNotifier: UpdateNotifier by lazy {
        AndroidUpdateNotifier(context)
    }

    val updateManager: UpdateManager by lazy {
        val appVersionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
        UpdateManager(
            currentVersion = appVersionName,
            updateChecker = updateChecker,
            updateNotifier = updateNotifier,
            appPreferences = appPreferences,
            scope = appScope,
        )
    }

    fun settingsViewModelFactory(): ViewModelProvider.Factory =
        SettingsViewModel.Factory(
            providerStore = providerStore,
            appPreferences = appPreferences,
            secureKeyStore = secureKeyStore,
            newTestProvider = { baseUrl: String, model: String, apiKey: String ->
                if (com.assistant.app.ui.settings.isGemini(baseUrl)) {
                    com.assistant.app.llm.GeminiProvider(
                        client = httpClient,
                        apiKey = apiKey,
                        model = model,
                        baseUrl = if (baseUrl == "gemini" || baseUrl.isBlank()) {
                            com.assistant.app.llm.GeminiProvider.DEFAULT_BASE_URL
                        } else {
                            baseUrl
                        },
                    )
                } else {
                    OpenAICompatibleProvider(httpClient, baseUrl, apiKey, model)
                }
            },
            ttsAvailable = voiceOutput.isAvailable,
            voiceOutput = voiceOutput,
            updateManager = updateManager,
        )
}
