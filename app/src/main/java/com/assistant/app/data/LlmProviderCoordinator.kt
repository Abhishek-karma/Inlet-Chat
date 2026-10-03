package com.assistant.app.data

import com.assistant.app.llm.GeminiProvider
import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.OpenAICompatibleProvider
import com.assistant.app.llm.model.thinkCapabilityFor
import com.assistant.app.ui.settings.isGemini
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Coordinates the active LLM provider's lifecycle and produces [ChatLlmState]
 * based on saved provider configurations and API keys.
 */
class LlmProviderCoordinator(
    private val providerStore: ProviderStore,
    private val httpClient: OkHttpClient,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _chatLlm = MutableStateFlow<ChatLlmState>(ChatLlmState.Loading)
    val chatLlm: StateFlow<ChatLlmState> = _chatLlm.asStateFlow()

    init {
        scope.launch(ioDispatcher) {
            providerStore.ensureSeeded()
            providerStore.activeProvider().collect { active ->
                _chatLlm.value = if (active == null) {
                    ChatLlmState.NeedsSetup
                } else {
                    val key = providerStore.apiKey(active.id)
                    val baseUrl = active.baseUrl.trim()
                    val model = active.model.trim()
                    if (baseUrl.isBlank() || model.isBlank() || key.isNullOrBlank()) {
                        ChatLlmState.NeedsSetup
                    } else {
                        val provider: LlmProvider = if (isGemini(baseUrl, active.name)) {
                            GeminiProvider(
                                client = httpClient,
                                apiKey = key,
                                model = model,
                                baseUrl = if (baseUrl == "gemini" || baseUrl.isBlank()) {
                                    GeminiProvider.DEFAULT_BASE_URL
                                } else {
                                    baseUrl
                                },
                            )
                        } else {
                            OpenAICompatibleProvider(httpClient, baseUrl, key, model)
                        }
                        ChatLlmState.Ready(
                            provider = provider,
                            model = model,
                            providerId = active.id,
                            name = active.name,
                            thinkCapability = thinkCapabilityFor(isGemini(baseUrl, active.name), model),
                        )
                    }
                }
            }
        }
    }
}
