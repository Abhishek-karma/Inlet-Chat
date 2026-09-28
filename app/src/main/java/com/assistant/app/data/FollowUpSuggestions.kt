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

    /** Below this an answer is too brief to suggest anything worth asking. */
    const val MIN_ANSWER_CHARS = 180

    fun isWorthSuggesting(answer: String): Boolean =
        answer.trim().length >= MIN_ANSWER_CHARS

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
        provider.stream(request).collect { chunk ->
            when (chunk) {
                is ChatChunk.Delta -> text.append(chunk.text)
                is ChatChunk.Reasoning -> Unit
                is ChatChunk.Done -> Unit
                is ChatChunk.Failure -> throw IllegalStateException(chunk.error.userMessage)
            }
        }
        return parse(text.toString())
    }

    /** Line-wise: strips bullets/numbering, drops blanks, caps at [MAX_ITEMS]. */
    internal fun parse(raw: String): List<String> = raw.lines()
        .map { it.trim().replace(Regex("^\\s*(?:[-*\u2022]|\\d+[.)])\\s*"), "") }
        .filter { it.isNotEmpty() }
        .take(MAX_ITEMS)
}
