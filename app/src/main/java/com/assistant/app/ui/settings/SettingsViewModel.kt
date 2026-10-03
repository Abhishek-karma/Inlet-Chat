package com.assistant.app.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assistant.app.data.ModelDraft
import com.assistant.app.data.ProviderDraft
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.local.ProviderModelEntity
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.AppTheme
import com.assistant.app.data.settings.SecureKeyStore
import com.assistant.app.data.settings.TextSize
import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.ProviderModelsClient
import com.assistant.app.llm.model.Role
import com.assistant.app.data.update.UpdateManager
import com.assistant.app.data.update.model.UpdateStatus
import com.assistant.app.voice.VoiceOption
import com.assistant.app.voice.VoiceOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface ConnectionOutcome {
    data object Success : ConnectionOutcome
    data class Failure(val message: String, val detail: String? = null) : ConnectionOutcome
}

data class ProviderSummary(
    val id: Long,
    val name: String,
    val activeModel: String?,
    val isActive: Boolean,
)

data class SettingsUiState(
    val providers: List<ProviderSummary> = emptyList(),
    val isEditing: Boolean = false,
    val editingId: Long? = null,
    val name: String = "",
    val baseUrl: String = "",
    /** Saved models being edited; the active one is flagged in [ModelDraft.isActive]. */
    val models: List<ModelDraft> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val isLoadingModels: Boolean = false,
    val modelsError: Boolean = false,
    val apiKeyInput: String = "",
    val storedKey: String? = null,
    val revealKey: Boolean = false,
    val voiceOutputEnabled: Boolean = false,
    val voiceAutoPlay: Boolean = true,
    val voiceSpeed: Float = 1.0f,
    val voiceOptions: List<VoiceOption> = emptyList(),
    val voicesLoaded: Boolean = false,
    val voiceId: String? = null,
    val appearance: AppTheme = AppTheme.SYSTEM,
    val textSize: TextSize = TextSize.NORMAL,
    val reasoningVisible: Boolean = true,
    val ttsAvailable: Boolean = true,
    val isLoaded: Boolean = false,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val formError: String? = null,
    val connectionOutcome: ConnectionOutcome? = null,
    val updateStatus: UpdateStatus = UpdateStatus.Idle,
    val autoCheckUpdates: Boolean = true,
    val showUpdateDialog: Boolean = false,
) {
    override fun toString(): String =
        "SettingsUiState(providers=$providers, isEditing=$isEditing, editingId=$editingId, " +
            "name=$name, baseUrl=$baseUrl, models=${models.size}, apiKeyInput=<redacted>, " +
            "storedKey=${if (storedKey != null) "<present>" else "null"}, " +
            "revealKey=$revealKey, voiceOutputEnabled=$voiceOutputEnabled, " +
            "appearance=$appearance, reasoningVisible=$reasoningVisible, " +
            "ttsAvailable=$ttsAvailable, " +
            "isLoaded=$isLoaded, " +
            "isSaving=$isSaving, isTesting=$isTesting, formError=$formError, " +
            "connectionOutcome=$connectionOutcome)"
}

/** Bridges the settings UI and ProviderStore for provider management. */
class SettingsViewModel(
    private val providerStore: ProviderStore,
    private val appPreferences: AppPreferences,
    private val secureKeyStore: SecureKeyStore,
    private val newTestProvider: (baseUrl: String, model: String, apiKey: String) -> LlmProvider,
    private val modelsClient: ProviderModelsClient = ProviderModelsClient(),
    private val ttsAvailable: Boolean = true,
    private val voiceOutput: VoiceOutput? = null,
    private val connectionTestDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updateManager: UpdateManager? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(ttsAvailable = ttsAvailable))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var validationJob: Job? = null

    init {
        viewModelScope.launch {
            providerStore.ensureSeeded()
            val voiceOutputEnabled = appPreferences.voiceOutputEnabled.first()
            val voiceAutoPlay = appPreferences.voiceAutoPlay.first()
            val voiceSpeed = appPreferences.voiceSpeed.first()
            val voiceId = appPreferences.voiceId.first()
            val appearance = appPreferences.appearance.first()
            val textSize = appPreferences.textSize.first()
            val reasoningVisible = appPreferences.reasoningVisible.first()
            _uiState.update {
                it.copy(
                    voiceOutputEnabled = voiceOutputEnabled,
                    voiceAutoPlay = voiceAutoPlay,
                    voiceSpeed = voiceSpeed,
                    voiceId = voiceId,
                    appearance = appearance,
                    textSize = textSize,
                    reasoningVisible = reasoningVisible,
                    isLoaded = true,
                )
            }
        }
        viewModelScope.launch {
            providerStore.providersWithModel().collect { list ->
                _uiState.update { state ->
                    state.copy(
                        providers = list.map { provider ->
                            ProviderSummary(provider.id, provider.name, provider.activeModel, provider.isActive)
                        },
                    )
                }
            }
        }
        if (updateManager != null) {
            viewModelScope.launch {
                updateManager.updateStatus.collect { status ->
                    _uiState.update {
                        it.copy(
                            updateStatus = status,
                            showUpdateDialog = status is UpdateStatus.Available,
                        )
                    }
                }
            }
            viewModelScope.launch {
                updateManager.autoCheckUpdates.collect { autoCheck ->
                    _uiState.update { it.copy(autoCheckUpdates = autoCheck) }
                }
            }
        }
    }

    /**
     * A device without TTS yields no voices, which is what keeps the picker
     * hidden there.
     */
    fun loadVoices() {
        val output = voiceOutput ?: return
        if (!ttsAvailable) return
        if (_uiState.value.voicesLoaded) return
        output.voices { options ->
            _uiState.update { it.copy(voiceOptions = options, voicesLoaded = true) }
        }
    }

    fun startAdd() {
        _uiState.update {
            it.copy(
                isEditing = true,
                editingId = null,
                name = "",
                baseUrl = "",
                models = listOf(ModelDraft(isActive = true)),
                apiKeyInput = "",
                storedKey = null,
                revealKey = false,
                formError = null,
                connectionOutcome = null,
            )
        }
        onFormChanged()
    }

    fun fillPreset(name: String, baseUrl: String, defaultModel: String) {
        _uiState.update {
            it.copy(
                name = name,
                baseUrl = baseUrl,
                models = listOf(ModelDraft(model = defaultModel, isActive = true)),
            )
        }
        onFormChanged()
    }

    fun edit(id: Long) {
        viewModelScope.launch {
            val entity = providerStore.provider(id) ?: return@launch
            _uiState.update {
                it.copy(
                    isEditing = true,
                    editingId = id,
                    name = entity.name,
                    baseUrl = entity.baseUrl,
                    models = providerStore.modelsOf(id).map { it.toDraft() },
                    apiKeyInput = "",
                    storedKey = providerStore.apiKey(id),
                    revealKey = false,
                    formError = null,
                    connectionOutcome = null,
                )
            }
            revalidate()
        }
    }

        fun cancelEdit() {
        _uiState.update {
            it.copy(
                isEditing = false,
                editingId = null,
                name = "",
                baseUrl = "",
                models = emptyList(),
                apiKeyInput = "",
                storedKey = null,
                revealKey = false,
                formError = null,
                connectionOutcome = null,
            )
        }
    }

    fun loadModels() {
        val state = _uiState.value
        val key = state.apiKeyInput.trim().ifEmpty { state.storedKey }
        if (state.baseUrl.isBlank() || key.isNullOrBlank() || state.isLoadingModels) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, modelsError = false) }
            val result = modelsClient.listModels(state.baseUrl, key)
            _uiState.update {
                it.copy(
                    isLoadingModels = false,
                    availableModels = result.getOrDefault(emptyList()),
                    modelsError = result.isFailure || result.getOrNull().isNullOrEmpty(),
                )
            }
        }
    }

    fun setName(value: String) = updateEditor { it.copy(name = value) }

    fun setBaseUrl(value: String) = updateEditor { it.copy(baseUrl = value) }

    private fun ProviderModelEntity.toDraft(): ModelDraft = ModelDraft(
        id = id,
        model = model,
        isActive = isActive,
    )

    /** Appends a new, non-active model draft. */
    fun addModel() = updateEditor { state ->
        state.copy(models = state.models + ModelDraft())
    }

    /** Removes the model at [index]; a remaining model inherits the active flag. */
    fun removeModel(index: Int) = updateEditor { state ->
        val remaining = state.models.toMutableList().also {
            if (index in it.indices) it.removeAt(index)
        }
        if (remaining.isNotEmpty() && remaining.none { it.isActive }) {
            remaining[0] = remaining[0].copy(isActive = true)
        }
        state.copy(models = remaining)
    }

    /** Edits the model at [index]. */
    fun updateModel(index: Int, transform: (ModelDraft) -> ModelDraft) = updateEditor { state ->
        state.copy(models = state.models.mapIndexed { i, draft -> if (i == index) transform(draft) else draft })
    }

    /** Makes the model at [index] the active one of this provider. */
    fun setActiveModel(index: Int) = updateEditor { state ->
        state.copy(models = state.models.mapIndexed { i, draft -> draft.copy(isActive = i == index) })
    }

    /** Selects a model id from the provider's `/models` list for row [index]. */
    fun setModelAt(index: Int, value: String) = updateModel(index) { it.copy(model = value) }

    fun setApiKeyInput(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
        onFormChanged()
    }

    fun setRevealKey(revealed: Boolean) {
        _uiState.update {
            if (revealed && it.apiKeyInput.isEmpty()) {
                it.copy(revealKey = true, apiKeyInput = it.storedKey.orEmpty())
            } else {
                it.copy(revealKey = revealed)
            }
        }
    }

    fun save() {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            val state = _uiState.value
            val draft = ProviderDraft(name = state.name.trim(), baseUrl = state.baseUrl.trim())
            val enteredKey = state.apiKeyInput.trim().ifEmpty { null }
            val error = providerStore.validate(state.editingId ?: 0, draft, state.models, enteredKey)
            _uiState.update { it.copy(formError = error) }
            if (error != null) return@launch
            _uiState.update { it.copy(isSaving = true) }
            try {
                if (state.editingId == null) {
                    providerStore.addProvider(draft, enteredKey, state.models)
                } else {
                    providerStore.updateProvider(state.editingId, draft, enteredKey, state.models)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(isSaving = false, formError = SAVE_FAILED) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isSaving = false,
                    isEditing = false,
                    editingId = null,
                    name = "",
                    baseUrl = "",
                    models = emptyList(),
                    apiKeyInput = "",
                    storedKey = null,
                    revealKey = false,
                    formError = null,
                )
            }
        }
    }

        fun delete(id: Long) {
        viewModelScope.launch {
            try {
                providerStore.deleteProvider(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed delete leaves the list and editor unchanged.
                return@launch
            }
            _uiState.update { state ->
                if (state.editingId == id) {
                    state.copy(
                        isEditing = false,
                        editingId = null,
                        name = "",
                        baseUrl = "",
                        models = emptyList(),
                        apiKeyInput = "",
                        storedKey = null,
                        revealKey = false,
                        formError = null,
                        connectionOutcome = null,
                    )
                } else {
                    state
                }
            }
        }
    }

        fun activateProvider(id: Long) {
        viewModelScope.launch { providerStore.setActive(id) }
    }


    fun testConnection() {
        if (_uiState.value.isTesting) return
        viewModelScope.launch {
            val state = _uiState.value
            val draft = ProviderDraft(state.name.trim(), state.baseUrl.trim())
            val enteredKey = state.apiKeyInput.trim().ifEmpty { null }
            val error = providerStore.validate(state.editingId ?: 0, draft, state.models, enteredKey)
            _uiState.update { it.copy(formError = error) }
            if (error != null) return@launch
            _uiState.update { it.copy(isTesting = true, connectionOutcome = null) }
            // An empty key field means "keep the stored key".
            val apiKey = enteredKey ?: state.storedKey.orEmpty()
            val testModel = state.models.firstOrNull { it.isActive }?.model
                ?: state.models.firstOrNull()?.model
                ?: ""
            val request = ChatRequest(
                model = testModel,
                messages = listOf(Role.USER to PING_MESSAGE),
            )
            val failure = try {
                withContext(connectionTestDispatcher) {
                    try {
                        val first = withTimeout(TEST_TIMEOUT_MS) {
                            newTestProvider(draft.baseUrl, testModel, apiKey)
                                .stream(request)
                                .firstOrNull { it !is ChatChunk.Done }
                        }
                        when (first) {
                            is ChatChunk.Failure -> first
                            // No chunk before the stream ended: empty response.
                            null -> ChatChunk.Failure(ProviderError.InvalidResponse)
                            // A delta arrived: connection, auth, and model work.
                            else -> null
                        }
                    } catch (e: TimeoutCancellationException) {
                        // A timeout is an outcome, not a cancellation.
                        ChatChunk.Failure(ProviderError.Timeout)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Class name only: messages could echo request configuration.
                Log.w(TAG, "Connection test failed (${e.javaClass.simpleName})")
                ChatChunk.Failure(ProviderError.Unknown)
            }
            _uiState.update {
                it.copy(
                    isTesting = false,
                    connectionOutcome = failure
                        ?.let { f -> ConnectionOutcome.Failure(f.error.userMessage, f.detail) }
                        ?: ConnectionOutcome.Success,
                )
            }
        }
    }


    fun setVoiceOutputEnabled(enabled: Boolean) {
        if (!ttsAvailable) return
        _uiState.update { it.copy(voiceOutputEnabled = enabled) }
        viewModelScope.launch {
            try {
                appPreferences.setVoiceOutputEnabled(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Revert on persist failure so the UI and store agree.
                _uiState.update { it.copy(voiceOutputEnabled = !enabled) }
            }
        }
    }

    fun setAppearance(theme: AppTheme) {
        val previous = _uiState.value.appearance
        _uiState.update { it.copy(appearance = theme) }
        viewModelScope.launch {
            try {
                appPreferences.setAppearance(theme)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(appearance = previous) }
            }
        }
    }

    fun setVoiceSpeed(speed: Float) {
        val previous = _uiState.value.voiceSpeed
        _uiState.update { it.copy(voiceSpeed = speed) }
        viewModelScope.launch {
            try {
                appPreferences.setVoiceSpeed(speed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(voiceSpeed = previous) }
            }
        }
    }

    /**
     * A voice whose pack was removed is stored anyway: the engine ignores
     * unknown ids at speak time, so this must not fail.
     */
    fun setVoiceId(voiceId: String?) {
        val previous = _uiState.value.voiceId
        _uiState.update { it.copy(voiceId = voiceId) }
        viewModelScope.launch {
            try {
                appPreferences.setVoiceId(voiceId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(voiceId = previous) }
            }
        }
    }

        fun setTextSize(size: TextSize) {
        val previous = _uiState.value.textSize
        _uiState.update { it.copy(textSize = size) }
        viewModelScope.launch {
            try {
                appPreferences.setTextSize(size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(textSize = previous) }
            }
        }
    }

        fun setVoiceAutoPlay(enabled: Boolean) {
        val previous = _uiState.value.voiceAutoPlay
        _uiState.update { it.copy(voiceAutoPlay = enabled) }
        viewModelScope.launch {
            try {
                appPreferences.setVoiceAutoPlay(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(voiceAutoPlay = previous) }
            }
        }
    }


    fun setReasoningVisible(visible: Boolean) {
        val previous = _uiState.value.reasoningVisible
        _uiState.update { it.copy(reasoningVisible = visible) }
        viewModelScope.launch {
            try {
                appPreferences.setReasoningVisible(visible)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(reasoningVisible = previous) }
            }
        }
    }

    private fun updateEditor(transform: (SettingsUiState) -> SettingsUiState) {
        _uiState.update(transform)
        onFormChanged()
    }

    private fun onFormChanged() {
        // A stale result no longer describes the edited config.
        _uiState.update { it.copy(connectionOutcome = null) }
        revalidate()
    }

    fun checkForUpdates() {
        val manager = updateManager ?: return
        viewModelScope.launch {
            manager.checkForUpdates(manual = true)
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        val manager = updateManager ?: return
        viewModelScope.launch {
            manager.setAutoCheckUpdates(enabled)
        }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    private fun revalidate() {
        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            val state = _uiState.value
            val draft = ProviderDraft(state.name.trim(), state.baseUrl.trim())
            val error = providerStore.validate(
                state.editingId ?: 0,
                draft,
                state.models,
                state.apiKeyInput.trim().ifEmpty { null },
            )
            _uiState.update { it.copy(formError = error) }
        }
    }

    class Factory(
        private val providerStore: ProviderStore,
        private val appPreferences: AppPreferences,
        private val secureKeyStore: SecureKeyStore,
        private val newTestProvider: (baseUrl: String, model: String, apiKey: String) -> LlmProvider,
        private val modelsClient: ProviderModelsClient = ProviderModelsClient(),
        private val ttsAvailable: Boolean = true,
        private val voiceOutput: VoiceOutput? = null,
        private val updateManager: UpdateManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                "Unknown ViewModel class: $modelClass"
            }
            return SettingsViewModel(
                providerStore,
                appPreferences,
                secureKeyStore,
                newTestProvider,
                modelsClient,
                ttsAvailable,
                voiceOutput,
                updateManager = updateManager,
            ) as T
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        const val PING_MESSAGE = "ping"
        const val SAVE_FAILED = "Could not save settings."

        const val TEST_TIMEOUT_MS = 30_000L
    }
}
