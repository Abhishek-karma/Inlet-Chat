package com.assistant.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException

interface PageFetcher {
    suspend fun fetch(url: String): String?
}

class HttpPageFetcher(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBytes: Int = MAX_PAGE_BYTES,
) : PageFetcher {

    override suspend fun fetch(url: String): String? = withContext(dispatcher) {
        val parsed = url.toHttpUrlOrNull() ?: return@withContext null
        if (parsed.scheme != "https" && parsed.scheme != "http") return@withContext null
        if (isPrivateHost(parsed.host)) return@withContext null

        val request = Request.Builder()
            .url(parsed)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null

                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.isNotEmpty() && !isTextContentType(contentType)) {
                    return@withContext null
                }

                val contentLength = body.contentLength()
                if (contentLength > maxBytes * 2) {
                    return@withContext null
                }

                val inputStream = body.byteStream()
                val buffer = ByteArray(4096)
                val out = ByteArrayOutputStream()
                var totalRead = 0
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    totalRead += read
                    if (totalRead >= maxBytes) break
                }

                val charset = body.contentType()?.charset() ?: Charsets.UTF_8
                out.toString(charset.name())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isTextContentType(contentType: String): Boolean =
        contentType.contains("text/html") ||
            contentType.contains("text/plain") ||
            contentType.contains("application/xhtml+xml") ||
            contentType.contains("application/xml") ||
            contentType.contains("text/markdown")

    /**
     * Search results are attacker-influenceable, so a result pointing at
     * loopback, link-local, or a private range must not be fetched: that would
     * let a page reach services on the user's own network or device.
     */
    internal fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            return true
        }
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull()?.let(::isOctet) == true }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            if (a == 10 || a == 127 || a == 0) return true
            if (a == 192 && b == 168) return true
            if (a == 172 && b in 16..31) return true
            if (a == 169 && b == 254) return true
        }
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return true
        return false
    }

    private fun isOctet(value: Int): Boolean = value in 0..255

    companion object {
        const val MAX_PAGE_BYTES = 512 * 1024 // 512 KB
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
