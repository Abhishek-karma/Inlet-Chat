package com.assistant.app.llm

import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.model.ReasoningConfig
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
 * Native streaming client for the Google Gemini API.
 *
 * Posts to `{baseUrl}/v1beta/models/{model}:streamGenerateContent?alt=sse`
 * with `x-goog-api-key` header authentication.
 *
 * Formats multi-turn user/model history, system instructions, and inline image data,
 * parsing Server-Sent Events to emit [ChatChunk.Delta], [ChatChunk.Reasoning], and [ChatChunk.Done].
 *
 * API keys and sensitive text are never logged.
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : LlmProvider {

    override fun stream(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val call = try {
            client.newCall(httpRequest(request))
        } catch (_: IllegalArgumentException) {
            trySend(ChatChunk.Failure(ProviderError.Unknown))
            close()
            return@callbackFlow
        }

        var settled = false

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
                            if (!call.isCanceled()) finish(ioFailure(e))
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) finish(ioFailure(e))
                }
            },
        )

        awaitClose { call.cancel() }
    }

    private fun readStream(
        source: BufferedSource,
        sendDelta: (ChatChunk) -> Boolean,
        settle: (ChatChunk) -> Unit,
    ) {
        var answerSeen = false

        while (true) {
            when (val line = readLine(source)) {
                SseLine.Eof -> break
                SseLine.Overlong -> {
                    settle(ChatChunk.Failure(ProviderError.InvalidResponse))
                    return
                }
                is SseLine.Line -> {
                    val text = line.text.trim()
                    if (text.startsWith("data:")) {
                        val jsonStr = text.substring(5).trim()
                        if (jsonStr.isNotEmpty()) {
                            val chunks = parseGeminiPayload(jsonStr)
                            if (chunks == null) {
                                settle(ChatChunk.Failure(ProviderError.InvalidResponse))
                                return
                            }
                            for (chunk in chunks) {
                                when (chunk) {
                                    is ChatChunk.Failure -> {
                                        settle(chunk)
                                        return
                                    }
                                    is ChatChunk.Delta, is ChatChunk.Reasoning -> {
                                        if (!sendDelta(chunk)) return
                                        if (chunk is ChatChunk.Delta && chunk.text.isNotEmpty()) {
                                            answerSeen = true
                                        }
                                    }
                                    is ChatChunk.Done -> {
                                        // Final stop marker received
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        settle(
            if (answerSeen) ChatChunk.Done
            else ChatChunk.Failure(ProviderError.InvalidResponse),
        )
    }

    private fun parseGeminiPayload(data: String): List<ChatChunk>? = try {
        val root = JSONObject(data)
        if (root.has("error")) {
            val errObj = root.optJSONObject("error")
            val msg = errObj?.optString("message") ?: "Gemini error"
            val code = errObj?.optInt("code", 0) ?: 0
            val status = errObj?.optString("status").orEmpty()
            val lowerMsg = msg.lowercase()
            val lowerStatus = status.lowercase()

            val isKeyError = lowerMsg.contains("api key") ||
                lowerMsg.contains("api_key") ||
                lowerMsg.contains("unregistered callers") ||
                lowerStatus == "unauthenticated" ||
                lowerMsg.contains("api key not valid")

            val err = when {
                isKeyError -> ProviderError.InvalidCredentials
                code == 401 -> ProviderError.AuthenticationFailed
                code == 403 -> {
                    if (isKeyError) ProviderError.InvalidCredentials else ProviderError.AuthenticationFailed
                }
                code == 404 || lowerMsg.contains("not found") -> ProviderError.ModelNotFound
                code == 429 || lowerStatus == "resource_exhausted" || lowerMsg.contains("quota") || lowerMsg.contains("rate limit") -> {
                    if (lowerMsg.contains("quota") || lowerStatus == "resource_exhausted") {
                        ProviderError.QuotaExceeded
                    } else {
                        ProviderError.RateLimited
                    }
                }
                code == 400 && (lowerMsg.contains("model") || lowerMsg.contains("not found")) -> ProviderError.ModelNotFound
                code == 400 -> ProviderError.UnsupportedRequest
                code in 500..599 -> ProviderError.ServerError
                else -> ProviderError.Unknown
            }
            listOf(ChatChunk.Failure(err, msg))
        } else {
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                emptyList()
            } else {
                buildList {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val isThought = part.optBoolean("thought", false) || part.has("thought")
                            val text = part.optString("text")
                            if (text.isNotEmpty()) {
                                if (isThought) {
                                    add(ChatChunk.Reasoning(text))
                                } else {
                                    add(ChatChunk.Delta(text))
                                }
                            }
                        }
                    }
                }
            }
        }
    } catch (_: JSONException) {
        null
    }

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
            scanned = source.buffer.size
            if (!source.request(scanned + 1)) {
                val text = if (scanned == 0L) "" else source.readUtf8(scanned)
                val line = text.removeSuffix("\r")
                return if (line.isEmpty()) SseLine.Eof else SseLine.Line(line)
            }
        }
    }

    private fun statusFailure(code: Int, body: String?): ChatChunk.Failure {
        val errorMsg = extractErrorMessage(body)
        val errorStatus = extractErrorStatus(body)
        val lowerMsg = errorMsg?.lowercase().orEmpty()
        val lowerStatus = errorStatus?.lowercase().orEmpty()

        val isKeyError = lowerMsg.contains("api key") ||
            lowerMsg.contains("api_key") ||
            lowerMsg.contains("unregistered callers") ||
            lowerStatus == "unauthenticated" ||
            lowerMsg.contains("api key not valid")

        val error = when {
            isKeyError -> ProviderError.InvalidCredentials
            code == 401 -> ProviderError.AuthenticationFailed
            code == 403 -> {
                if (isKeyError) ProviderError.InvalidCredentials else ProviderError.AuthenticationFailed
            }
            code == 404 || lowerMsg.contains("not found") -> ProviderError.ModelNotFound
            code == 429 || lowerStatus == "resource_exhausted" || lowerMsg.contains("quota") || lowerMsg.contains("rate limit") -> {
                if (lowerMsg.contains("quota") || lowerStatus == "resource_exhausted") {
                    ProviderError.QuotaExceeded
                } else {
                    ProviderError.RateLimited
                }
            }
            code == 400 && (lowerMsg.contains("model") || lowerMsg.contains("not found")) -> ProviderError.ModelNotFound
            code == 400 -> ProviderError.UnsupportedRequest
            code in 500..599 -> ProviderError.ServerError
            else -> ProviderError.Unknown
        }

        val detail = when {
            error == ProviderError.ModelNotFound -> errorMsg ?: "Model not found. Verify the model name."
            error == ProviderError.InvalidCredentials -> errorMsg ?: "Invalid API key. Check the key and try again."
            error == ProviderError.AuthenticationFailed -> errorMsg ?: "Authentication failed."
            error == ProviderError.QuotaExceeded -> errorMsg ?: "Quota exceeded. Check your plan or billing."
            error == ProviderError.RateLimited -> errorMsg ?: "Rate limit reached. Try again shortly."
            error == ProviderError.ServerError -> errorMsg ?: "Gemini service error. Try again later."
            error == ProviderError.UnsupportedRequest -> errorMsg ?: "Unsupported request."
            else -> errorMsg
        }
        return ChatChunk.Failure(error, detail)
    }

    private fun extractErrorMessage(body: String?): String? = try {
        body?.let { JSONObject(it).optJSONObject("error")?.optString("message") }
            ?.ifEmpty { null }
    } catch (_: JSONException) {
        null
    }

    private fun extractErrorStatus(body: String?): String? = try {
        body?.let { JSONObject(it).optJSONObject("error")?.optString("status") }
            ?.ifEmpty { null }
    } catch (_: JSONException) {
        null
    }

    private fun readErrorBody(body: ResponseBody?): String? {
        if (body == null) return null
        val source = body.source()
        return if (source.request(MAX_ERROR_BODY_BYTES + 1)) null else source.readUtf8()
    }

    private fun ioFailure(e: IOException): ChatChunk.Failure =
        ChatChunk.Failure(
            error = if (e is SocketTimeoutException) ProviderError.Timeout else ProviderError.NetworkUnavailable,
            detail = if (e is SocketTimeoutException) "Request timed out." else "Network unavailable. Check your connection.",
        )

    private fun httpRequest(request: ChatRequest): Request {
        val payload = JSONObject()

        // 1. System instructions
        val systemTexts = request.messages
            .filter { it.first == Role.SYSTEM && it.second.isNotBlank() }
            .map { it.second }
        if (systemTexts.isNotEmpty()) {
            val sysInstruction = JSONObject().apply {
                val parts = JSONArray()
                parts.put(JSONObject().put("text", systemTexts.joinToString("\n\n")))
                put("parts", parts)
            }
            payload.put("systemInstruction", sysInstruction)
        }

        // 2. Multi-turn conversation contents
        val contents = JSONArray()
        val nonSystem = request.messages.filter { it.first != Role.SYSTEM }

        var currentRole: String? = null
        var currentParts: JSONArray? = null

        fun flushTurn() {
            if (currentRole != null && currentParts != null && currentParts!!.length() > 0) {
                contents.put(
                    JSONObject().apply {
                        put("role", currentRole)
                        put("parts", currentParts)
                    },
                )
            }
        }

        nonSystem.forEachIndexed { index, (role, text) ->
            val geminiRole = if (role == Role.USER) "user" else "model"
            val isLastUser = (role == Role.USER && index == nonSystem.indexOfLast { it.first == Role.USER })

            if (geminiRole != currentRole) {
                flushTurn()
                currentRole = geminiRole
                currentParts = JSONArray()
            }

            if (text.isNotBlank()) {
                currentParts?.put(JSONObject().put("text", text))
            }

            if (isLastUser && request.images.isNotEmpty()) {
                request.images.forEach { dataUrl ->
                    val commaIndex = dataUrl.indexOf(',')
                    val mimeType = if (dataUrl.startsWith("data:") && commaIndex > 5) {
                        dataUrl.substring(5, commaIndex).substringBefore(';')
                    } else {
                        "image/jpeg"
                    }
                    val base64Data = if (commaIndex != -1) dataUrl.substring(commaIndex + 1) else dataUrl
                    if (base64Data.isNotBlank()) {
                        currentParts?.put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject()
                                    .put("mimeType", mimeType)
                                    .put("data", base64Data.trim()),
                            ),
                        )
                    }
                }
            }
        }
        flushTurn()

        if (contents.length() == 0) {
            contents.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", "Hello")))
                },
            )
        }

        payload.put("contents", contents)

        // Reasoning control: a thinking budget, an explicit off, or (Gemini 3)
        // a thinking level. Auto/null sends nothing, keeping provider defaults.
        val thinkingConfig = JSONObject()
        when (val reasoning = request.reasoning) {
            ReasoningConfig.Off -> thinkingConfig.put("thinkingBudget", 0)
            is ReasoningConfig.Budget -> thinkingConfig.put(
                "thinkingBudget",
                reasoning.tokens.coerceIn(MIN_THINKING_BUDGET, MAX_THINKING_BUDGET),
            )
            is ReasoningConfig.Effort -> thinkingConfig.put("thinkingLevel", reasoning.level.name.lowercase())
            else -> Unit
        }
        if (thinkingConfig.length() > 0) {
            payload.put("generationConfig", JSONObject().put("thinkingConfig", thinkingConfig))
        }

        val effectiveModel = request.model.ifBlank { model }.trim().removePrefix("models/")
        val rawBase = baseUrl.trim().trimEnd('/')
        val base = when {
            rawBase.isEmpty() || rawBase.equals("gemini", ignoreCase = true) -> DEFAULT_BASE_URL
            else -> rawBase
        }
        val normalizedBase = if (base.endsWith("/v1beta") || base.endsWith("/v1")) base else "$base/v1beta"
        val url = "$normalizedBase/models/$effectiveModel:streamGenerateContent?alt=sse"

        return Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey.trim())
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private sealed interface SseLine {
        data class Line(val text: String) : SseLine
        data object Overlong : SseLine
        data object Eof : SseLine
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private const val MAX_ERROR_BODY_BYTES = 64L * 1024
        private const val MAX_LINE_BYTES = 64L * 1024
        private const val MIN_THINKING_BUDGET = 1
        private const val MAX_THINKING_BUDGET = 32768
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
