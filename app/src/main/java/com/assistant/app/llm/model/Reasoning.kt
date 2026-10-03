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
 * What reasoning control the active provider is configured to expose.
 * [Unsupported] and [Unknown] both mean "no reasoning parameter may be sent";
 * the difference is whether support is explicitly absent or simply not known.
 */
sealed interface ThinkCapability {
    data object Unsupported : ThinkCapability

    /** No capability information is available; treated exactly like unsupported. */
    data object Unknown : ThinkCapability

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

/** True when reasoning is safe to send (an explicit [ThinkCapability.Effort] or [ThinkCapability.Budget]). */
fun ThinkCapability.isReasoningSupported(): Boolean =
    this is ThinkCapability.Effort || this is ThinkCapability.Budget

/** True when [config] is a selection [capability] can express as-is. */
fun ThinkCapability.accepts(config: ReasoningConfig): Boolean = when (this) {
    ThinkCapability.Unsupported, ThinkCapability.Unknown -> false
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
        ThinkCapability.Unsupported, ThinkCapability.Unknown -> ReasoningConfig.Auto
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

private const val GEMINI_BUDGET_MIN = 1
private const val GEMINI_BUDGET_MAX = 32768

/** Marks in a model id that mean the provider exposes discrete effort. */
private val EFFORT_MARKERS = listOf("thinking", "reasoner", "reasoning")
private val EFFORT_ID_PATTERN = Regex("""(?:^|[^a-z0-9])(?:o[1-9]|r1)(?:$|[^0-9])""")

/** Capability detected from the selected model's id; unrecognised ids send nothing. */
fun inferThinkCapability(model: String): ThinkCapability {
    val id = model.lowercase()
    return when {
        "gemini" in id -> ThinkCapability.Budget(
            minTokens = GEMINI_BUDGET_MIN,
            maxTokens = GEMINI_BUDGET_MAX,
            allowOff = true,
            allowAuto = true,
        )
        EFFORT_MARKERS.any { it in id } || EFFORT_ID_PATTERN.containsMatchIn(id) ->
            ThinkCapability.Effort(ReasoningEffort.entries)
        else -> ThinkCapability.Unknown
    }
}

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
