package com.assistant.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.AttachmentIngester
import com.assistant.app.llm.model.UiAttachment
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavior contract for [AttachmentIngester] storage: images decode into a
 * stored JPEG copy, text files store capped, and oversized or non-image
 * sources fail with user-facing messages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttachmentIngesterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val ingester = AttachmentIngester(context, Dispatchers.Unconfined)

    /** A valid 1x1 JPEG, so decoding exercises the real image path. */
    private val jpeg1x1: ByteArray = java.io.ByteArrayOutputStream().also { out ->
        android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
    }.toByteArray()

    @Test
    fun storeImageStoresJpegCopy() {
        val result = ingester.storeImage(ByteArrayInputStream(jpeg1x1), "photo.jpg")

        val attachment = (result as AttachmentIngester.IngestResult.Success).attachment
        assertEquals(UiAttachment.Kind.IMAGE, attachment.kind)
        assertEquals("image/jpeg", attachment.mime)
        assertEquals("photo.jpg", attachment.displayName)
        assertTrue(attachment.path.startsWith(ingester.attachmentsDir.absolutePath))
        assertTrue(java.io.File(attachment.path).exists())
        assertTrue(attachment.sizeBytes > 0)
    }

    @Test
    fun storeTextStoresContent() {
        val result = ingester.storeText(ByteArrayInputStream("hello".toByteArray()), "notes.txt")

        val attachment = (result as AttachmentIngester.IngestResult.Success).attachment
        assertEquals(UiAttachment.Kind.TEXT, attachment.kind)
        assertEquals("text/plain", attachment.mime)
        assertEquals("hello", java.io.File(attachment.path).readText())
    }

    @Test
    fun storeTextRejectsOversizedSource() {
        val oversized = ByteArray((AttachmentIngester.MAX_TEXT_BYTES + 1).toInt())

        val result = ingester.storeText(ByteArrayInputStream(oversized), "big.txt")

        assertEquals(AttachmentIngester.TEXT_TOO_LARGE_MESSAGE, (result as AttachmentIngester.IngestResult.Failure).message)
    }

    @Test
    fun storeImageRejectsNonImageSource() {
        val result = ingester.storeImage(ByteArrayInputStream("not an image".toByteArray()), "x.jpg")

        assertEquals(AttachmentIngester.UNSUPPORTED_MESSAGE, (result as AttachmentIngester.IngestResult.Failure).message)
    }
}
