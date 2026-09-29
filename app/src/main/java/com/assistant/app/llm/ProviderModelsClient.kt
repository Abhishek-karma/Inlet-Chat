package com.assistant.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lists the models an OpenAI-compatible endpoint serves, via
 * `GET {baseUrl}/models`, so a self-hosted endpoint shows what it really has.
 *
 * Failures are returned, not thrown: a server without `/models` is common and
 * must not block manual entry.
 */
class ProviderModelsClient(
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * The model ids the endpoint serves, or a failure the caller can present.
     * An empty list means nothing usable came back; the caller falls back to
     * typing.
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val trimmedBase = baseUrl.trim().trimEnd('/')
            if (trimmedBase.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("base URL is empty"))
            }
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("API key is empty"))
            }
            val isGeminiEndpoint = baseUrl.contains("generativelanguage.googleapis.com", ignoreCase = true) ||
                baseUrl.trim().lowercase() == "gemini"
            val url = if (isGeminiEndpoint) {
                val base = if (trimmedBase == "gemini") "https://generativelanguage.googleapis.com" else trimmedBase
                val norm = if (base.endsWith("/v1beta") || base.endsWith("/v1")) base else "$base/v1beta"
                "$norm/models"
            } else {
                "$trimmedBase/models"
            }
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
            if (isGeminiEndpoint) {
                requestBuilder.header("x-goog-api-key", trimmedKey)
            } else {
                requestBuilder.header("Authorization", "Bearer $trimmedKey")
            }
            val request = requestBuilder.build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorText = try {
                            val raw = response.body?.string()
                            JSONObject(raw.orEmpty()).optJSONObject("error")?.optString("message")
                        } catch (_: Exception) {
                            null
                        }
                        val msg = errorText?.ifBlank { null } ?: "HTTP ${response.code}"
                        return@withContext Result.failure(
                            IOException(msg),
                        )
                    }
                    val bodySource = response.body?.source()
                        ?: return@withContext Result.failure(IOException("models response had no body"))
                    bodySource.request(MAX_RESPONSE_BYTES + 1)
                    if (bodySource.buffer.size > MAX_RESPONSE_BYTES) {
                        return@withContext Result.failure(
                            IOException("models response exceeded ${MAX_RESPONSE_BYTES} bytes"),
                        )
                    }
                    val json = bodySource.readUtf8()
                    val models = if (isGeminiEndpoint) parseGeminiModelIds(json) else parseModelIds(json)
                    Result.success(models)
                }
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: JSONException) {
                Result.failure(e)
            }
        }

    /** Pulls `models[].name` out of the Gemini list envelope. */
    private fun parseGeminiModelIds(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val array = try {
            JSONObject(body).optJSONArray("models")
        } catch (e: JSONException) {
            throw IOException("models response was not JSON", e)
        } ?: return emptyList()
        val ids = buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val methods = item.optJSONArray("supportedGenerationMethods")
                var canGenerate = false
                if (methods != null) {
                    for (m in 0 until methods.length()) {
                        if (methods.optString(m) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }
                } else {
                    canGenerate = true
                }
                if (canGenerate) {
                    val rawName = item.optString("name").trim()
                    val cleanName = rawName.removePrefix("models/")
                    if (cleanName.isNotEmpty()) add(cleanName)
                }
            }
        }
        return ids.distinct().sorted()
    }

    /** Pulls `data[].id` out of the OpenAI-compatible list envelope. */
    private fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        val array = try {
            JSONObject(body).optJSONArray("data")
        } catch (e: JSONException) {
            // An HTML error page or proxy response is a reported failure, not
            // an exception escaping to the caller.
            throw IOException("models response was not JSON", e)
        } ?: return emptyList()
        val ids = buildList {
            for (i in 0 until array.length()) {
                val id = array.optJSONObject(i)?.optString("id")?.trim().orEmpty()
                if (id.isNotEmpty()) add(id)
            }
        }
        // Servers do not guarantee an order; sorting keeps the list stable
        // between openings.
        return ids.distinct().sorted()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 512L * 1024

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
