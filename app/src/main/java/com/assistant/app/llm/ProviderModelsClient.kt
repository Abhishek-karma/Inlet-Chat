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
            val request = Request.Builder()
                .url("$trimmedBase/models")
                .header("Authorization", "Bearer $trimmedKey")
                .header("Accept", "application/json")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("models request failed (HTTP ${response.code})"),
                        )
                    }
                    val bodySource = response.body?.source()
                        ?: return@withContext Result.failure(IOException("models response had no body"))
                    // Cap the read: a misbehaving endpoint must not be able
                    // to grow memory without limit.
                    bodySource.request(MAX_RESPONSE_CHARS + 1)
                    val body = bodySource.readUtf8().take(MAX_RESPONSE_CHARS.toInt())
                    Result.success(parseModelIds(body))
                }
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: JSONException) {
                Result.failure(e)
            }
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
        private const val MAX_RESPONSE_CHARS = 512L * 1024

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
