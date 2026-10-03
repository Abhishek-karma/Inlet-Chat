package com.assistant.app.llm.model

/**
 * Provider-neutral reasoning selection for one generation. The UI picks a
 * value from what the active model's [ThinkCapability] offers; providers
 * translate it into their own request parameters, sending nothing for [Auto].
 */
sealed interface ReasoningConfig {
    data object Auto : ReasoningConfig
    data object Off : ReasoningConfig
    data class Effort(val level: ReasoningEffort) : ReasoningConfig
    data class Budget(val tokens: Int) : ReasoningConfig
}

enum class ReasoningEffort { LOW, MEDIUM, HIGH }

/**
 * What reasoning control the active model actually exposes. [Unsupported]
 * means no reasoning parameter may be sent; the UI hides the control and the
 * request carries no reasoning config.
 */
sealed interface ThinkCapability {
    data object Unsupported : ThinkCapability

    /** Discrete levels, e.g. OpenAI `reasoning_effort` or Gemini `thinkingLevel`. */
    data class Effort(val levels: List<ReasoningEffort>) : ThinkCapability

    /**
     * A numeric thinking budget in tokens, e.g. Gemini `thinkingBudget`.
     * [allowOff] and [allowAuto] say whether 0 and dynamic are accepted.
     */
    data class Budget(
        val minTokens: Int,
        val maxTokens: Int,
        val allowOff: Boolean,
        val allowAuto: Boolean,
    ) : ThinkCapability
}

/** True when [config] is a selection [capability] can express as-is. */
fun ThinkCapability.accepts(config: ReasoningConfig): Boolean = when (this) {
    ThinkCapability.Unsupported -> false
    is ThinkCapability.Effort -> config is ReasoningConfig.Effort && config.level in levels
    is ThinkCapability.Budget -> when (config) {
        ReasoningConfig.Auto -> allowAuto
        ReasoningConfig.Off -> allowOff
        is ReasoningConfig.Budget -> config.tokens in minTokens..maxTokens
        is ReasoningConfig.Effort -> false
    }
}

/** Coerces [config] into the closest selection [capability] supports. */
fun ThinkCapability.normalize(config: ReasoningConfig): ReasoningConfig {
    if (accepts(config)) return config
    return when (this) {
        ThinkCapability.Unsupported -> ReasoningConfig.Auto
        is ThinkCapability.Effort -> ReasoningConfig.Auto
        is ThinkCapability.Budget -> when (config) {
            is ReasoningConfig.Budget -> ReasoningConfig.Budget(config.tokens.coerceIn(minTokens, maxTokens))
            ReasoningConfig.Off -> if (allowOff) ReasoningConfig.Off else ReasoningConfig.Auto
            else -> ReasoningConfig.Auto
        }
    }
}

/** Deterministic budget choices offered for a budget-based model. */
fun ThinkCapability.budgetPresets(): List<Int> = when (this) {
    is ThinkCapability.Budget ->
        (listOf(minTokens, maxTokens) + listOf(1024, 2048, 4096, 8192, 16384, 32768))
            .filter { it in minTokens..maxTokens }
            .distinct()
            .sorted()
    else -> emptyList()
}

/**
 * The reasoning capability of a model, from its id alone. Only model families
 * with documented reasoning controls are matched; everything else is
 * [ThinkCapability.Unsupported], so no speculative parameter is ever sent.
 */
fun thinkCapabilityFor(isGeminiProvider: Boolean, model: String): ThinkCapability {
    val id = model.trim().lowercase().removePrefix("models/").substringAfterLast('/')
    return if (isGeminiProvider) {
        when {
            id.startsWith("gemini-2.5-pro") -> ThinkCapability.Budget(128, 32768, allowOff = false, allowAuto = true)
            id.startsWith("gemini-2.5-flash-lite") -> ThinkCapability.Budget(512, 24576, allowOff = true, allowAuto = false)
            id.startsWith("gemini-2.5-flash") -> ThinkCapability.Budget(1, 24576, allowOff = true, allowAuto = true)
            id.startsWith("gemini-3") -> ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
            else -> ThinkCapability.Unsupported
        }
    } else {
        when {
            O_SERIES_PATTERN.matches(id) || id.startsWith("gpt-5") ->
                ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH))
            else -> ThinkCapability.Unsupported
        }
    }
}

private val O_SERIES_PATTERN = Regex("^o[134]($|[-.]).*|^o[134]$")

/** Compact stable encoding for persistence; [decodeReasoningConfig] reverses it. */
fun ReasoningConfig.encode(): String = when (this) {
    ReasoningConfig.Auto -> "auto"
    ReasoningConfig.Off -> "off"
    is ReasoningConfig.Effort -> "effort:${level.name.lowercase()}"
    is ReasoningConfig.Budget -> "budget:$tokens"
}

fun decodeReasoningConfig(raw: String): ReasoningConfig? = when {
    raw == "auto" -> ReasoningConfig.Auto
    raw == "off" -> ReasoningConfig.Off
    raw.startsWith("effort:") -> ReasoningEffort.entries
        .firstOrNull { it.name.lowercase() == raw.removePrefix("effort:") }
        ?.let { ReasoningConfig.Effort(it) }
    raw.startsWith("budget:") -> raw.removePrefix("budget:").toIntOrNull()
        ?.takeIf { it > 0 }
        ?.let { ReasoningConfig.Budget(it) }
    else -> null
}
