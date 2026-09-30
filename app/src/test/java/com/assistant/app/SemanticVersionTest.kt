package com.assistant.app

import com.assistant.app.data.update.SemanticVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {

    @Test
    fun parseStandardVersions() {
        val v1 = SemanticVersion.parse("1.2.3")
        assertNotNull(v1)
        assertEquals(1, v1?.major)
        assertEquals(2, v1?.minor)
        assertEquals(3, v1?.patch)

        val v2 = SemanticVersion.parse("v2.0.1")
        assertNotNull(v2)
        assertEquals(2, v2?.major)
        assertEquals(0, v2?.minor)
        assertEquals(1, v2?.patch)

        val v3 = SemanticVersion.parse("V10.20.30")
        assertNotNull(v3)
        assertEquals(10, v3?.major)
        assertEquals(20, v3?.minor)
        assertEquals(30, v3?.patch)
    }

    @Test
    fun compareNumericPartsProperly() {
        assertTrue(SemanticVersion.isNewer(latest = "1.10.0", current = "1.9.0"))
        assertTrue(SemanticVersion.isNewer(latest = "2.0.0", current = "1.99.99"))
        assertTrue(SemanticVersion.isNewer(latest = "v1.0.1", current = "1.0.0"))
        assertTrue(SemanticVersion.isNewer(latest = "1.1.0", current = "1.0.9"))

        assertFalse(SemanticVersion.isNewer(latest = "1.0.0", current = "1.0.0"))
        assertFalse(SemanticVersion.isNewer(latest = "v1.0.0", current = "1.0.0"))
        assertFalse(SemanticVersion.isNewer(latest = "0.9.9", current = "1.0.0"))
        assertFalse(SemanticVersion.isNewer(latest = "1.0.0", current = "1.0.1"))
    }

    @Test
    fun handlePreReleaseVersions() {
        // Release is newer than pre-release of the same version
        assertTrue(SemanticVersion.isNewer(latest = "1.0.0", current = "1.0.0-rc1"))
        assertFalse(SemanticVersion.isNewer(latest = "1.0.0-rc1", current = "1.0.0"))

        // Higher patch pre-release is newer than lower patch release
        assertTrue(SemanticVersion.isNewer(latest = "1.0.1-rc1", current = "1.0.0"))
    }
}
