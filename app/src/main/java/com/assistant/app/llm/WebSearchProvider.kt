package com.assistant.app.llm

import com.assistant.app.llm.model.SearchError
import com.assistant.app.llm.model.SearchOutcome
import com.assistant.app.llm.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

interface WebSearchProvider {
    suspend fun search(query: String, maxResults: Int = 5): SearchOutcome
}

class SearXNGSearchProvider(
    private val client: OkHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val apiKey: String? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WebSearchProvider {

    override suspend fun search(query: String, maxResults: Int): SearchOutcome = withContext(dispatcher) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext SearchOutcome.Failure(SearchError.NoResults)
        }

        val url = endpoint.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", trimmed)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("categories", "general")
            ?.addQueryParameter("language", "auto")
            ?.build()
            ?: return@withContext SearchOutcome.Failure(SearchError.Unknown)

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)

        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            requestBuilder.header("X-Subscription-Token", apiKey.trim())
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext statusFailure(response.code)
                val body = response.body?.string()
                    ?: return@withContext SearchOutcome.Failure(SearchError.InvalidResponse)
                parseResults(body, maxResults)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SearchOutcome.Failure(
                if (e is SocketTimeoutException) SearchError.Timeout else SearchError.NetworkUnavailable,
            )
        } catch (_: Exception) {
            SearchOutcome.Failure(SearchError.Unknown)
        }
    }

    private fun statusFailure(code: Int): SearchOutcome.Failure = SearchOutcome.Failure(
        when {
            code == 401 || code == 403 -> SearchError.InvalidCredentials
            code == 429 -> SearchError.RateLimited
            code >= 500 -> SearchError.ServerError
            else -> SearchError.Unknown
        },
    )

    private fun parseResults(body: String, maxResults: Int): SearchOutcome = try {
        val json = JSONObject(body)
        val array = json.optJSONArray("results")
            ?: json.optJSONObject("web")?.optJSONArray("results")

        val seenUrls = mutableSetOf<String>()
        val results = buildList {
            if (array != null) {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val rawUrl = entry.optString("url").trim()
                    if (rawUrl.isEmpty() || (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://"))) {
                        continue
                    }

                    val normalizedUrl = normalizeUrl(rawUrl)
                    if (!seenUrls.add(normalizedUrl)) {
                        continue
                    }

                    val title = entry.optString("title").ifBlank { rawUrl }
                    val content = entry.optString("content")
                        .ifBlank { entry.optString("snippet") }
                        .ifBlank { entry.optString("description") }
                    val engine = entry.optString("engine").takeIf { it.isNotBlank() }

                    add(
                        SearchResult(
                            title = title,
                            url = rawUrl,
                            snippet = content,
                            engine = engine,
                        ),
                    )
                }
            }
        }.take(maxResults)

        if (results.isEmpty()) SearchOutcome.Failure(SearchError.NoResults)
        else SearchOutcome.Success(results)
    } catch (_: JSONException) {
        SearchOutcome.Failure(SearchError.InvalidResponse)
    }

    private fun normalizeUrl(url: String): String =
        url.trimEnd('/').lowercase()

    companion object {
        const val DEFAULT_ENDPOINT = "https://searx.be/search"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}
