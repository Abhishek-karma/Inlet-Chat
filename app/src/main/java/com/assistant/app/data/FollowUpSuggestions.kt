package com.assistant.app.data

import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.Role

/**
 * Asks the provider that produced the answer for follow-up questions.
 * Best-effort: the caller treats any failure as "no suggestions".
 *
 * This is a second billable request after every answer, so it is kept cheap:
 * a short timeout, a small cap, and no attempt at all for answers too short to
 * prompt a useful follow-up.
 */
object FollowUpSuggestions {

    const val TIMEOUT_MS = 6_000L
    const val MAX_ITEMS = 2

    private val ACKNOWLEDGEMENT_REGEX = Regex(
        "^(?i)(?:ok|okay|sure|got it|i see|done|all set|you're welcome|you are welcome|no problem|not at all|happy to help|anytime|understood|thanks|thank you|hello|hi|good morning|good afternoon|good evening)[.!]?$"
    )

    private val KNOWN_SHORT_ACKNOWLEDGEMENTS = setOf(
        "sure, i can help with that.",
        "sure, i can help with that!",
        "sure, i'd be happy to help.",
        "i understand.",
        "i understand completely.",
        "glad i could help!",
        "let me know if you need anything else.",
        "i'm ready when you are.",
    )

    /**
     * Determines whether an answer is substantive enough to suggest follow-up questions.
     * - Disqualifies empty or blank answers
     * - Disqualifies trivial conversational acknowledgements
     * - Disqualifies error/failure messages
     * - Allows concise, useful factual/informative answers
     * - Reliably allows normal length answers without an arbitrary cliff
     */
    fun isWorthSuggesting(answer: String): Boolean {
        val trimmed = answer.trim()
        if (trimmed.isEmpty() || trimmed.length < 12) return false
        if (isErrorOrRefusal(trimmed)) return false
        if (isAcknowledgement(trimmed)) return false
        return true
    }

    internal fun isAcknowledgement(text: String): Boolean {
        val clean = text.trim()
        if (ACKNOWLEDGEMENT_REGEX.matches(clean)) return true
        if (clean.length <= 40 && clean.lowercase() in KNOWN_SHORT_ACKNOWLEDGEMENTS) return true
        return false
    }

    internal fun isErrorOrRefusal(text: String): Boolean {
        val lower = text.lowercase()
        return lower.startsWith("error:") ||
            lower.startsWith("something went wrong") ||
            lower.startsWith("request timed out") ||
            lower.startsWith("network unavailable") ||
            lower.startsWith("quota exceeded") ||
            lower.startsWith("rate limited") ||
            lower.startsWith("invalid api key") ||
            lower.startsWith("authentication failed")
    }

    suspend fun generate(
        provider: LlmProvider,
        model: String,
        question: String,
        answer: String,
    ): List<String> {
        val instruction =
            "Suggest $MAX_ITEMS short follow-up questions the user might ask next. " +
                "Reply with only the questions, one per line."
        val request = ChatRequest(
            model = model,
            messages = listOf(
                Role.USER to question,
                Role.ASSISTANT to answer,
                Role.USER to instruction,
            ),
        )
        val text = StringBuilder()
        var failed = false
        provider.stream(request).collect { chunk ->
            when (chunk) {
                is ChatChunk.Delta -> text.append(chunk.text)
                is ChatChunk.Reasoning -> Unit
                is ChatChunk.Done -> Unit
                is ChatChunk.Failure -> failed = true
            }
        }
        if (failed) return emptyList()
        return parse(text.toString())
    }

    /**
     * Parses and normalizes LLM-generated follow-up questions.
     * Handles:
     * - Numbered lines (1. Question?, 1) Question?, 1 - Question?)
     * - Bulleted lines (- Question?, * Question?, • Question?)
     * - Wrapping quotes, markdown bold/italics
     * - Code fences and markdown headings
     * - Preamble meta lines (e.g. "Here are some suggestions:")
     * - Trailing meta text (e.g. "Hope this helps!")
     * - Case-insensitive deduplication
     * - Length validation and cap at [MAX_ITEMS]
     */
    internal fun parse(raw: String): List<String> {
        val lines = raw.lines()
        val result = mutableListOf<String>()
        val seenNormalized = mutableSetOf<String>()
        var insideCodeBlock = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("```")) {
                insideCodeBlock = !insideCodeBlock
                continue
            }
            if (insideCodeBlock) continue
            if (trimmed.startsWith("#")) continue
            if (isPreambleOrMeta(trimmed)) continue

            val cleaned = cleanSuggestionLine(trimmed)
            if (cleaned.length < 5 || cleaned.length > 180) continue
            if (isMetaText(cleaned)) continue

            val normalizedKey = cleaned.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (normalizedKey.isNotEmpty() && seenNormalized.add(normalizedKey)) {
                result.add(cleaned)
                if (result.size >= MAX_ITEMS) break
            }
        }
        return result
    }

    private fun isPreambleOrMeta(line: String): Boolean {
        val lower = line.lowercase()
        return (lower.endsWith(":") && (lower.contains("suggest") || lower.contains("question") || lower.contains("follow"))) ||
            lower.startsWith("here are") ||
            lower.startsWith("certainly") ||
            lower.startsWith("sure, here") ||
            lower.contains("follow-up question") ||
            lower.contains("questions you might") ||
            lower.contains("questions you could ask") ||
            lower.contains("hope this helps") ||
            lower.contains("feel free to ask") ||
            lower.contains("let me know if")
    }

    private fun isMetaText(text: String): Boolean {
        val lower = text.lowercase()
        return lower in setOf("none", "n/a", "no suggestions", "no questions", "no follow-ups")
    }

    private fun cleanSuggestionLine(line: String): String {
        var text = line.trim()
        text = text.replace(Regex("^\\s*(?:[\\-*•–—+]|\\d+[.)\\-:])\\s*"), "")
        text = text.replace(Regex("^[*_]+"), "").replace(Regex("[*_]+$"), "")
        text = text.replace(Regex("^[\"\'“”«]+"), "").replace(Regex("[\"\'“”»]+$"), "")
        return text.trim()
    }
}
