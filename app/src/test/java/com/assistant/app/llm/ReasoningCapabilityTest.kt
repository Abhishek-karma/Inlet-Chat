package com.assistant.app.llm

import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ReasoningEffort
import com.assistant.app.llm.model.ThinkCapability
import com.assistant.app.llm.model.budgetPresets
import com.assistant.app.llm.model.decodeReasoningConfig
import com.assistant.app.llm.model.encode
import com.assistant.app.llm.model.normalize
import com.assistant.app.llm.model.thinkCapabilityFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability derivation from model ids, config normalization, budget presets,
 * and the persistence encoding. Pure JVM: no Android types involved.
 */
class ReasoningCapabilityTest {

    @Test
    fun `gemini 2_5 pro supports a bounded budget without off`() {
        val capability = thinkCapabilityFor(isGeminiProvider = true, model = "gemini-2.5-pro")
        assertEquals(ThinkCapability.Budget(128, 32768, allowOff = false, allowAuto = true), capability)
    }

    @Test
    fun `gemini 2_5 flash supports off, auto, and a bounded budget`() {
        val capability = thinkCapabilityFor(isGeminiProvider = true, model = "gemini-2.5-flash")
        assertEquals(ThinkCapability.Budget(1, 24576, allowOff = true, allowAuto = true), capability)
    }

    @Test
    fun `gemini 2_5 flash-lite has no auto`() {
        val capability = thinkCapabilityFor(isGeminiProvider = true, model = "gemini-2.5-flash-lite")
        assertEquals(ThinkCapability.Budget(512, 24576, allowOff = true, allowAuto = false), capability)
    }

    @Test
    fun `gemini 3 models are effort based`() {
        val capability = thinkCapabilityFor(isGeminiProvider = true, model = "gemini-3-pro-preview")
        assertEquals(ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)), capability)
    }

    @Test
    fun `older gemini models are unsupported`() {
        assertEquals(ThinkCapability.Unsupported, thinkCapabilityFor(true, "gemini-1.5-pro"))
        assertEquals(ThinkCapability.Unsupported, thinkCapabilityFor(true, "gemini-2.0-flash"))
    }

    @Test
    fun `openai o-series models support effort`() {
        assertEquals(
            ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            thinkCapabilityFor(false, "o3-mini"),
        )
        assertEquals(
            ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            thinkCapabilityFor(false, "o4-mini-2025-04-16"),
        )
        assertEquals(
            ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            thinkCapabilityFor(false, "openai/o3-mini"),
        )
    }

    @Test
    fun `gpt-5 models support effort`() {
        assertEquals(
            ThinkCapability.Effort(listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)),
            thinkCapabilityFor(false, "gpt-5-mini"),
        )
    }

    @Test
    fun `unknown models are unsupported for both provider kinds`() {
        assertEquals(ThinkCapability.Unsupported, thinkCapabilityFor(false, "gpt-4o-mini"))
        assertEquals(ThinkCapability.Unsupported, thinkCapabilityFor(false, "llama3.1:8b"))
        assertEquals(ThinkCapability.Unsupported, thinkCapabilityFor(true, "text-embedding-004"))
        assertEquals(ThinkCapability.Unsupported, thinkCapabilityFor(false, ""))
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
    fun `normalize on unsupported always falls back to auto`() {
        assertEquals(
            ReasoningConfig.Auto,
            ThinkCapability.Unsupported.normalize(ReasoningConfig.Budget(1024)),
        )
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
