package com.assistant.app.llm

import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ReasoningEffort
import com.assistant.app.llm.model.ThinkCapability
import com.assistant.app.llm.model.accepts
import com.assistant.app.llm.model.budgetPresets
import com.assistant.app.llm.model.decodeReasoningConfig
import com.assistant.app.llm.model.encode
import com.assistant.app.llm.model.inferThinkCapability
import com.assistant.app.llm.model.isReasoningSupported
import com.assistant.app.llm.model.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability detection from the selected model's id, and the selection model. */
class ReasoningCapabilityTest {

    @Test
    fun `gemini model ids are detected as budget reasoning models`() {
        assertEquals(
            ThinkCapability.Budget(1, 32768, allowOff = true, allowAuto = true),
            inferThinkCapability("gemini-2.5-flash"),
        )
        assertTrue(inferThinkCapability("gemini-3-pro-preview").isReasoningSupported())
    }

    @Test
    fun `known reasoning model ids are detected as effort models`() {
        listOf("o3", "o3-mini", "deepseek-r1", "grok-3-thinking", "qwen3-reasoner").forEach { id ->
            assertEquals(ThinkCapability.Effort(ReasoningEffort.entries), inferThinkCapability(id))
            assertTrue(inferThinkCapability(id).isReasoningSupported())
        }
    }

    @Test
    fun `plain model ids stay unknown`() {
        listOf("gpt-4o", "gpt-4o-mini", "llama3.1:8b", "phi-4", "mistral-small", "foo").forEach { id ->
            assertEquals(ThinkCapability.Unknown, inferThinkCapability(id))
            assertFalse(inferThinkCapability(id).isReasoningSupported())
        }
    }

    @Test
    fun `unknown and unsupported accept nothing and normalize to auto`() {
        listOf(ThinkCapability.Unknown, ThinkCapability.Unsupported).forEach { capability ->
            assertFalse(capability.isReasoningSupported())
            assertFalse(capability.accepts(ReasoningConfig.Budget(1024)))
            assertFalse(capability.accepts(ReasoningConfig.Effort(ReasoningEffort.HIGH)))
            assertEquals(ReasoningConfig.Auto, capability.normalize(ReasoningConfig.Budget(1024)))
        }
    }

    @Test
    fun `normalize clamps an out-of-range budget and drops incompatible selections`() {
        val budget = ThinkCapability.Budget(512, 24576, allowOff = true, allowAuto = true)
        assertEquals(ReasoningConfig.Budget(8192), budget.normalize(ReasoningConfig.Budget(8192)))
        assertEquals(ReasoningConfig.Budget(512), budget.normalize(ReasoningConfig.Budget(64)))
        assertEquals(ReasoningConfig.Budget(24576), budget.normalize(ReasoningConfig.Budget(99_999)))
        assertEquals(ReasoningConfig.Off, budget.normalize(ReasoningConfig.Off))
        assertEquals(ReasoningConfig.Auto, budget.normalize(ReasoningConfig.Effort(ReasoningEffort.HIGH)))

        val effort = ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        assertEquals(ReasoningConfig.Effort(ReasoningEffort.LOW), effort.normalize(ReasoningConfig.Effort(ReasoningEffort.LOW)))
        assertEquals(ReasoningConfig.Auto, effort.normalize(ReasoningConfig.Effort(ReasoningEffort.MEDIUM)))
        assertEquals(ReasoningConfig.Auto, effort.normalize(ReasoningConfig.Off))
        assertEquals(ReasoningConfig.Auto, effort.normalize(ReasoningConfig.Budget(1024)))
    }

    @Test
    fun `budget presets stay within the supported range`() {
        val presets = ThinkCapability.Budget(128, 32768, allowOff = false, allowAuto = true).budgetPresets()
        assertTrue(presets.contains(128))
        assertTrue(presets.contains(32768))
        assertEquals(presets, presets.distinct().sorted())
        assertTrue(presets.all { it in 128..32768 })
    }

    @Test
    fun `encode and decode round-trip every config kind`() {
        val configs = listOf(
            ReasoningConfig.Auto,
            ReasoningConfig.Off,
            ReasoningConfig.Effort(ReasoningEffort.MEDIUM),
            ReasoningConfig.Budget(4096),
        )
        configs.forEach { config ->
            assertEquals(config, decodeReasoningConfig(config.encode()))
        }
        assertNull(decodeReasoningConfig("nonsense"))
        assertNull(decodeReasoningConfig("budget:not-a-number"))
        assertNull(decodeReasoningConfig("budget:0"))
        assertNull(decodeReasoningConfig("effort:extreme"))
    }
}
