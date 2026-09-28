package com.assistant.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.assistant.app.llm.model.UiAttachment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies picked or captured files into app storage. Images are downscaled and
 * re-encoded as JPEG (the stored copy is what requests send as a data-URL);
 * text-like files are stored capped, to be inlined as context.
 *
 * Failures come back as message strings, never exceptions: an unreadable or
 * oversized file must not disturb the chat.
 */
class AttachmentIngester(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    val attachmentsDir: File = File(appContext.filesDir, ATTACHMENTS_DIR)

    sealed interface IngestResult {
        data class Success(val attachment: UiAttachment) : IngestResult
        data class Failure(val message: String) : IngestResult
    }

    suspend fun ingestImage(uri: Uri): IngestResult = withContext(ioDispatcher) {
        try {
            val name = queryDisplayName(uri) ?: DEFAULT_IMAGE_NAME
            val result = resolver.openInputStream(uri)?.use { stream -> storeImage(stream, name) }
                ?: IngestResult.Failure(UNREADABLE_MESSAGE)
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            IngestResult.Failure(UNREADABLE_MESSAGE)
        } finally {
            reclaimCameraCapture(uri)
        }
    }

    /** The staged camera capture is copied; its temp file is not needed. */
    private fun reclaimCameraCapture(uri: Uri) {
        if (uri.authority == "${appContext.packageName}.fileprovider") {
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    suspend fun ingestText(uri: Uri): IngestResult = withContext(ioDispatcher) {
        try {
            val name = queryDisplayName(uri) ?: DEFAULT_TEXT_NAME
            when {
                !isSupportedText(resolver.getType(uri), name) -> IngestResult.Failure(UNSUPPORTED_MESSAGE)
                else -> resolver.openInputStream(uri)?.use { stream -> storeText(stream, name) }
                    ?: IngestResult.Failure(UNREADABLE_MESSAGE)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            IngestResult.Failure(UNREADABLE_MESSAGE)
        }
    }

    internal fun storeImage(stream: InputStream, displayName: String): IngestResult {
        // Bounded read: this is the only full copy in memory, and decoding is
        // sampled from it (bounds need a separate decode pass).
        val bytes = stream.readBounded(MAX_SOURCE_IMAGE_BYTES)
            ?: return IngestResult.Failure(IMAGE_TOO_LARGE_MESSAGE)
        // Signature check first: garbage input is rejected deterministically
        // instead of relying on decoder-specific failure behavior.
        if (!hasImageSignature(bytes)) return IngestResult.Failure(UNSUPPORTED_MESSAGE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return IngestResult.Failure(UNSUPPORTED_MESSAGE)
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            },
        ) ?: return IngestResult.Failure(UNREADABLE_MESSAGE)
        val scaled = scaleDown(decoded, MAX_DIMENSION)
        val encoded = ByteArrayOutputStream().also { buffer ->
            scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, buffer)
        }
        val file = newFile("jpg")
        file.writeBytes(encoded.toByteArray())
        return IngestResult.Success(
            UiAttachment(
                id = UUID.randomUUID().toString(),
                kind = UiAttachment.Kind.IMAGE,
                displayName = displayName,
                mime = "image/jpeg",
                path = file.absolutePath,
                sizeBytes = file.length(),
            ),
        )
    }

    /** Reads one text-like file, capped. */
    internal fun storeText(stream: InputStream, displayName: String): IngestResult {
        val bytes = stream.readBounded(MAX_TEXT_BYTES)
            ?: return IngestResult.Failure(TEXT_TOO_LARGE_MESSAGE)
        val file = newFile("txt")
        file.writeText(bytes.toString(Charsets.UTF_8))
        return IngestResult.Success(
            UiAttachment(
                id = UUID.randomUUID().toString(),
                kind = UiAttachment.Kind.TEXT,
                displayName = displayName,
                mime = mimeFor(displayName) ?: "text/plain",
                path = file.absolutePath,
                sizeBytes = file.length(),
            ),
        )
    }

    private fun queryDisplayName(uri: Uri): String? = resolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    private fun newFile(extension: String): File {
        attachmentsDir.mkdirs()
        return File(attachmentsDir, "${UUID.randomUUID()}.$extension")
    }

    /** Checks the declared MIME type, falling back to the file extension. */
    private fun isSupportedText(mime: String?, name: String): Boolean {
        if (mime != null) {
            if (mime.startsWith("text/")) return true
            if (mime in SUPPORTED_TEXT_MIME_TYPES) return true
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_TEXT_EXTENSIONS
    }

    private fun mimeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html" -> "text/html"
        "js", "ts" -> "text/javascript"
        else -> null
    }

    companion object {
        const val MAX_DIMENSION = 1280
        const val IMAGE_QUALITY = 85
        const val MAX_SOURCE_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_TEXT_BYTES = 100L * 1024

        const val MAX_IMAGES_PER_MESSAGE = 4
        const val MAX_TEXTS_PER_MESSAGE = 2

        const val UNREADABLE_MESSAGE = "Could not read the file."
        const val UNSUPPORTED_MESSAGE = "Unsupported file type."
        const val IMAGE_TOO_LARGE_MESSAGE = "Image is too large (max 10 MB)."
        const val TEXT_TOO_LARGE_MESSAGE = "File is too large (max 100 KB)."

        private const val ATTACHMENTS_DIR = "attachments"
        private const val DEFAULT_IMAGE_NAME = "photo.jpg"
        private const val DEFAULT_TEXT_NAME = "file.txt"

        private val SUPPORTED_TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-yaml",
            "application/toml",
        )

        private val SUPPORTED_TEXT_EXTENSIONS = setOf(
            "txt", "md", "csv", "json", "xml", "yaml", "yml", "toml", "html", "css",
            "js", "ts", "kt", "java", "py", "rb", "go", "rs", "c", "h", "cpp", "cs",
            "sh", "gradle", "kts", "properties", "sql", "log",
        )

        /** Largest power-of-two sample size that still overshoots [target]. */
        private fun sampleSize(width: Int, height: Int, target: Int): Int {
            var sample = 1
            while (width / (sample
 * 2) >= target && height / (sample
 * 2) >= target) sample *= 2
            return sample
        }

        /** JPEG, PNG, or WEBP magic bytes — the formats Android decodes reliably. */
        private fun hasImageSignature(bytes: ByteArray): Boolean = when {            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> true
            bytes.size >= 4 &&
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> true
            bytes.size >= 12 &&
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> true
            else -> false
        }

        private fun scaleDown(bitmap: Bitmap, target: Int): Bitmap {
            val largest = maxOf(bitmap.width, bitmap.height)
            if (largest <= target) return bitmap
            val ratio = target.toFloat() / largest
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width
 * ratio).toInt().coerceAtLeast(1),
                (bitmap.height
 * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }

        private fun InputStream.readBounded(max: Long): ByteArray? {
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = read(chunk)
                if (read == -1) break
                total += read
                if (total > max) return null
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }
}
