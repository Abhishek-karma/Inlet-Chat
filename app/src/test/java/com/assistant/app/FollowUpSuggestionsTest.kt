package com.assistant.app

import com.assistant.app.data.FollowUpSuggestions
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.ProviderError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying follow-up question suitability filtering, parsing, and generation.
 */
class FollowUpSuggestionsTest {

    @Test
    fun followUpParsing_numbered() {
        val raw = """
            1. What is the capital of France?
            2. How many people live in Paris?
            3. What is the currency?
        """.trimIndent()
        val parsed = FollowUpSuggestions.parse(raw)
        assertEquals(
            listOf("What is the capital of France?", "How many people live in Paris?"),
            parsed,
        )
    }

    @Test
    fun followUpParsing_bulleted() {
        val raw = """
            - What is the speed of light?
            * Can anything travel faster than light?
            • How was it measured?
        """.trimIndent()
        val parsed = FollowUpSuggestions.parse(raw)
        assertEquals(
            listOf("What is the speed of light?", "Can anything travel faster than light?"),
            parsed,
        )
    }

    @Test
    fun followUpParsing_malformed() {
        val raw = """
            Here are some follow-up questions you might ask:
            ```markdown
            # Suggestions
            ```
            1) "Can you show an example in Kotlin?"
            2. **What are the performance implications?**
            1. Can you show an example in Kotlin?
            Hope this helps! Feel free to ask more.
        """.trimIndent()
        val parsed = FollowUpSuggestions.parse(raw)
        assertEquals(
            listOf(
                "Can you show an example in Kotlin?",
                "What are the performance implications?",
            ),
            parsed,
        )
    }

    @Test
    fun followUpParsing_stripsQuotesAndExcessiveWhitespace() {
        val raw = """
            "What is Room Database?"
            'How does SQLite compare?'
        """.trimIndent()
        val parsed = FollowUpSuggestions.parse(raw)
        assertEquals(
            listOf("What is Room Database?", "How does SQLite compare?"),
            parsed,
        )
    }

    @Test
    fun isWorthSuggesting_emptyAndBlankAnswers() {
        assertFalse(FollowUpSuggestions.isWorthSuggesting(""))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("   "))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("\n\t"))
    }

    @Test
    fun isWorthSuggesting_trivialAcknowledgements() {
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Ok"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Okay."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Got it!"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Sure."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Done."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("You're welcome!"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("No problem!"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Sure, I can help with that."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Understood."))
    }

    @Test
    fun isWorthSuggesting_errorsAndFailures() {
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Error: Invalid API key"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Something went wrong"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Request timed out"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Network unavailable"))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("Quota exceeded"))
    }

    @Test
    fun isWorthSuggesting_naturalRefusals() {
        assertFalse(FollowUpSuggestions.isWorthSuggesting("I can't help with that."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("I’m unable to assist with that request."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("I can't provide instructions for that."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("I cannot assist with that."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("I apologize, but I cannot fulfill this request."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("As an AI, I am unable to assist with this."))
        assertFalse(FollowUpSuggestions.isWorthSuggesting("My safety guidelines do not allow me to assist with this."))
    }

    @Test
    fun isWorthSuggesting_veryShortLegitimateAnswers() {
        assertTrue(FollowUpSuggestions.isWorthSuggesting("Paris."))
        assertTrue(FollowUpSuggestions.isWorthSuggesting("42"))
        assertTrue(FollowUpSuggestions.isWorthSuggesting("Kotlin 2.1"))
        assertTrue(FollowUpSuggestions.isWorthSuggesting("O(log n)"))
        assertTrue(FollowUpSuggestions.isWorthSuggesting("Yes, you can."))
    }

    @Test
    fun isWorthSuggesting_conciseInformativeAnswers() {
        assertTrue(FollowUpSuggestions.isWorthSuggesting("Paris is the capital of France."))
        assertTrue(FollowUpSuggestions.isWorthSuggesting("Water boils at 100°C at standard pressure."))
        assertTrue(FollowUpSuggestions.isWorthSuggesting("Use `ls -la` to list all files with details."))
        assertTrue(FollowUpSuggestions.isWorthSuggesting("The speed of light in vacuum is approximately 299,792,458 m/s."))
    }

    @Test
    fun isWorthSuggesting_normalLengthAnswers() {
        val normalAnswer = "Jetpack Compose is Android's recommended modern toolkit for building native UI. " +
            "It simplifies and accelerates UI development on Android with less code, powerful tools, and intuitive Kotlin APIs."
        assertTrue(FollowUpSuggestions.isWorthSuggesting(normalAnswer))
    }

    @Test
    fun followUpGeneration_success() = runTest {
        val provider = FakeLlmProvider(
            listOf(
                ScriptedEvent.Emit("1. How do I configure room compiler?\n"),
                ScriptedEvent.Emit("2. Can I use KSP with Kotlin 2.0?"),
            ),
        )
        val suggestions = FollowUpSuggestions.generate(
            provider = provider,
            model = "test-model",
            question = "How do I add Room to Android?",
            answer = "Add androidx.room dependencies and configure KSP plugin in build.gradle.kts.",
        )
        assertEquals(
            listOf(
                "How do I configure room compiler?",
                "Can I use KSP with Kotlin 2.0?",
            ),
            suggestions,
        )
        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests.first().messages.any { it.second.contains("Suggest 2 short follow-up questions") })
    }

    @Test
    fun followUpGeneration_providerFailure() = runTest {
        val provider = FakeLlmProvider(
            listOf(
                ScriptedEvent.Fail(ProviderError.ServerError),
            ),
        )
        val suggestions = FollowUpSuggestions.generate(
            provider = provider,
            model = "test-model",
            question = "Question",
            answer = "Substantive answer here.",
        )
        assertTrue(suggestions.isEmpty())
    }
}
