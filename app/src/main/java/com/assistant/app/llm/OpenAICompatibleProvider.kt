package com.assistant.app.llm

import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.model.Role
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Streams chat completions from an OpenAI-compatible endpoint.
 *
 * Posts to `{baseUrl}/chat/completions` and reads the response line by line as
 * it arrives. Frames go through [SseParser]; each delta becomes a [ChatChunk].
 *
 * Errors are emitted as [ChatChunk.Failure] — nothing escapes the flow, and
 * every stream settles exactly once. Cancelling cancels the HTTP call.
 *
 * Never logs: the request carries the API key, the body carries user text.
 */
class OpenAICompatibleProvider(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) : LlmProvider {

    override fun stream(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val call = try {
            client.newCall(httpRequest(request))
        } catch (_: IllegalArgumentException) {
            // Un-parseable base URL from configuration; nothing to connect to.
            trySend(ChatChunk.Failure(ProviderError.Unknown))
            close()
            return@callbackFlow
        }

        var settled = false

        /** Emits the terminal chunk and closes the flow; runs at most once. */
        fun finish(chunk: ChatChunk) {
            if (!settled) {
                settled = true
                trySendBlocking(chunk)
                close()
            }
        }

        call.enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!response.isSuccessful) {
                                finish(statusFailure(response.code, readErrorBody(response.body)))
                                return
                            }
                            val body = response.body
                            if (body == null) {
                                finish(ChatChunk.Failure(ProviderError.InvalidResponse))
                                return
                            }
                            readStream(
                                source = body.source(),
                                sendDelta = { chunk -> !trySendBlocking(chunk).isClosed },
                                settle = ::finish,
                            )
                        } catch (e: IOException) {
                            // If the collector cancelled, the flow is already closing.
                            if (!call.isCanceled()) finish(ioFailure(e))
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) finish(ioFailure(e))
                }
            },
        )

        // Cancelling the call interrupts the blocked read and releases the connection.
        awaitClose { call.cancel() }
    }

    /**
     * Reads [source] line by line, emitting one [ChatChunk.Delta] per text
     * delta. [sendDelta] returns false once the collector is gone; [settle]
     * sends the terminal chunk.
     */
    private fun readStream(
        source: BufferedSource,
        sendDelta: (ChatChunk) -> Boolean,
        settle: (ChatChunk) -> Unit,
    ) {
        val parser = SseParser()
        var contentSeen = false

        /** Handles one event; false when reading must stop. */
        fun handle(event: SseEvent): Boolean {
            when {
                event.data == DONE_MARKER -> {
                    // An empty response is a failure; reasoning counts as output.
                    settle(
                        if (contentSeen) ChatChunk.Done
                        else ChatChunk.Failure(ProviderError.InvalidResponse),
                    )
                    return false
                }

                event.event == ERROR_EVENT -> {
                    settle(errorEventFailure(event.data))
                    return false
                }

                else -> {
                    val chunks = deltasOf(event.data)
                    if (chunks == null) {
                        settle(ChatChunk.Failure(ProviderError.InvalidResponse))
                        return false
                    }
                    for (chunk in chunks) {
                        if (chunk is ChatChunk.Delta || chunk is ChatChunk.Reasoning) {
                            if (!sendDelta(chunk)) return false
                            contentSeen = true
                        }
                    }
                }
            }
            return true
        }

        while (true) {
            when (val read = readLine(source)) {
                SseLine.Eof -> break
                SseLine.Overlong -> {
                    // Discard everything after the cap and fail the stream.
                    settle(ChatChunk.Failure(ProviderError.InvalidResponse))
                    return
                }
                is SseLine.Line -> {
                    for (event in parser.parseSse("${read.text}\n")) {
                        if (!handle(event)) return
                    }
                }
            }
        }
        // EOF without [DONE] is tolerated; flush catches a final frame the
        // server closed without its blank line.
        for (event in parser.flush()) {
            if (!handle(event)) return
        }
        settle(
            if (contentSeen) ChatChunk.Done
            else ChatChunk.Failure(ProviderError.InvalidResponse),
        )
    }

    /**
     * Maps a mid-stream `event: error` frame: a JSON error object surfaces as
     * Unknown with the provider's message as detail; anything else is
     * ServerError.
     */
    private fun errorEventFailure(data: String): ChatChunk.Failure {
        val detail = errorMessage(data)
        val error = if (detail == null) ProviderError.ServerError else ProviderError.Unknown
        return ChatChunk.Failure(error, detail)
    }

    /**
     * Reads the next SSE line without its terminator. Bytes are only scanned
     * here — the line is decoded exactly once, when complete — so a multi-byte
     * character straddling a read boundary cannot be torn into U+FFFD
     * replacements (UTF-8 continuation bytes are never 0x0A, so a line boundary
     * is always a character boundary). A line longer than [MAX_LINE_BYTES]
     * yields [SseLine.Overlong]; a final unterminated line under the cap is
     * tolerated.
     */
    private fun readLine(source: BufferedSource): SseLine {
        var scanned = 0L
        while (true) {
            val newline = source.buffer.indexOf('\n'.code.toByte(), scanned)
            if (newline != -1L) {
                val text = source.readUtf8(newline)
                source.skip(1)
                return SseLine.Line(text.removeSuffix("\r"))
            }
            if (source.buffer.size > MAX_LINE_BYTES) return SseLine.Overlong
            scanned = source.buffer.size // do not rescan known bytes
            // Wait for one more byte without draining the buffer, so nothing is
            // decoded mid-line.
            if (!source.request(scanned + 1)) {
                val text = if (scanned == 0L) "" else source.readUtf8(scanned)
                // EOF: a trailing CR was a line terminator, not content.
                val line = text.removeSuffix("\r")
                return if (line.isEmpty()) SseLine.Eof else SseLine.Line(line)
            }
        }
    }

    /**
     * The deltas of one chat-completions chunk: content, reasoning
     * (`reasoning_content` or `reasoning`), both, or none — or null when the
     * payload is not valid JSON.
     */
    private fun deltasOf(data: String): List<ChatChunk>? = try {
        val choices = JSONObject(data).optJSONArray("choices")
        when {
            choices == null || choices.length() == 0 -> emptyList()
            else -> {
                val delta = choices.getJSONObject(0).optJSONObject("delta")
                if (delta == null) {
                    emptyList()
                } else {
                    buildList {
                        val reasoning = delta.optString("reasoning_content")
                            .ifEmpty { delta.optString("reasoning") }
                        if (reasoning.isNotEmpty()) add(ChatChunk.Reasoning(reasoning))
                        val content = delta.optString("content")
                        if (content.isNotEmpty()) add(ChatChunk.Delta(content))
                    }
                }
            }
        }
    } catch (_: JSONException) {
        null
    }

    /** Maps non-2xx statuses. */
    private fun statusFailure(code: Int, body: String?): ChatChunk.Failure {
        val error = when {
            code == 401 || code == 403 -> ProviderError.InvalidCredentials
            code == 429 -> ProviderError.RateLimited
            code >= 500 -> ProviderError.ServerError
            else -> ProviderError.Unknown
        }
        // The provider's own text is kept as detail for unmapped statuses only;
        // the user-facing message always comes from the enum.
        val detail = if (error == ProviderError.Unknown) errorMessage(body) else null
        return ChatChunk.Failure(error, detail)
    }

    /** The `error.message` string providers put in JSON error bodies, if any. */
    private fun errorMessage(body: String?): String? = try {
        body
            ?.let { JSONObject(it).optJSONObject("error")?.optString("message") }
            ?.ifEmpty { null }
    } catch (_: JSONException) {
        null
    }

    /**
     * Reads the error body for detail extraction, capped: a body larger than
     * [MAX_ERROR_BODY_BYTES] returns null, so a hostile endpoint cannot grow
     * memory without limit.
     */
    private fun readErrorBody(body: ResponseBody?): String? {
        if (body == null) return null
        val source = body.source()
        return if (source.request(MAX_ERROR_BODY_BYTES + 1)) null else source.readUtf8()
    }

    /** Timeout vs. connect-level failure. */
    private fun ioFailure(e: IOException): ChatChunk.Failure =
        ChatChunk.Failure(
            if (e is SocketTimeoutException) ProviderError.Timeout else ProviderError.NetworkUnavailable,
        )

    private fun httpRequest(request: ChatRequest): Request {
        val payload = JSONObject().apply {
            put("model", request.model.ifBlank { model })
            put("stream", true)
            put("messages", JSONArray().apply {
                request.messages.forEachIndexed { index, (role, content) ->
                    val message = JSONObject().put("role", role.name.lowercase())
                    // With images attached, the final user message becomes a
                    // multi-content array; other messages stay plain strings so
                    // text-only providers are unaffected.
                    if (role == Role.USER && index == request.messages.lastIndex && request.images.isNotEmpty()) {
                        val parts = JSONArray()
                        if (content.isNotBlank()) {
                            parts.put(JSONObject().put("type", "text").put("text", content))
                        }
                        request.images.forEach { url ->
                            parts.put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", url)),
                            )
                        }
                        message.put("content", parts)
                    } else {
                        message.put("content", content)
                    }
                    put(message)
                }
            })
        }
        val trimmedKey = apiKey.trim()
        val requestBuilder = Request.Builder()
            .url(baseUrl.trimEnd('/') + PATH)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (trimmedKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $trimmedKey")
        }
        return requestBuilder.build()
    }

    private companion object {
        const val PATH = "/chat/completions"
        const val DONE_MARKER = "[DONE]"

        /** SSE event name some providers use for mid-stream failures. */
        const val ERROR_EVENT = "error"

        /** Cap on the error-body bytes read for detail extraction. */
        const val MAX_ERROR_BODY_BYTES = 64L * 1024

        /** Cap on a single SSE line; longer lines fail the stream. */
        const val MAX_LINE_BYTES = 64L * 1024

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Outcome of one bounded line read from the SSE stream. */
private sealed interface SseLine {
    /** A complete line, or an unterminated tail tolerated before EOF. */
    data class Line(val text: String) : SseLine

    /**
     * The line exceeded [MAX_LINE_BYTES]; the stream fails and the response is
     * closed, so nothing after the cap is read.
     */
    data object Overlong : SseLine

    data object Eof : SseLine
}
