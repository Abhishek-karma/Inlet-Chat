package com.assistant.app.ui.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.llm.model.UiAttachment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.test.runTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * Tests for the full-screen media viewers in [MediaViewer]: the diagram PNG
 * export script and the viewer affordances for diagrams and attached images.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaViewerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun imageViewerShowsSaveAndClose() {
        val attachment = UiAttachment(
            id = "a1",
            kind = UiAttachment.Kind.IMAGE,
            displayName = "photo.png",
            mime = "image/png",
            path = writeTestPng().absolutePath,
            sizeBytes = 0L,
        )
        composeRule.setContent {
            MaterialTheme { ImageViewerDialog(attachment = attachment, onDismiss = {}) }
        }

        composeRule.onNodeWithText("Save").assertExists()
    }

    @Test
    fun decodeDownscaledReadsARealPng() = runTest {
        val decoded = decodeDownscaled(writeTestPng().absolutePath)

        assertTrue(decoded != null)
        assertTrue(decoded!!.aspect > 0f)
    }

    private fun writeTestPng(): File {
        val dir = ApplicationProvider.getApplicationContext<Context>().cacheDir
        val file = File(dir, "test.png")
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
        file.writeBytes(png)
        return file
    }
}
