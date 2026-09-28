package com.assistant.app.llm

import com.assistant.app.llm.model.SearchError
import com.assistant.app.llm.model.SearchOutcome
import com.assistant.app.llm.model.SearchResult
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

/**
 * Web search for answer context, via the Brave Search API.
 *
 * One provider, one key, one endpoint. Every failure becomes a
 * [SearchOutcome.Failure] so the caller can answer without search rather than
 * fail the turn. The key is sent in a header and never logged or rendered.
 */
class WebSearchClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    /** Overridable so tests can point at a local server. */
    private val endpoint: String = ENDPOINT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun search(query: String, maxResults: Int = 5): SearchOutcome = withContext(dispatcher) {
        if (apiKey.isBlank()) return@withContext SearchOutcome.Failure(SearchError.NoResults)

        val url = endpoint.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("count", maxResults.toString())
            ?.build()
            ?: return@withContext SearchOutcome.Failure(SearchError.Unknown)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", apiKey)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext statusFailure(response.code)
                val body = response.body?.string()
                    ?: return@withContext SearchOutcome.Failure(SearchError.InvalidResponse)
                parseResults(body, maxResults)
            }
        } catch (e: IOException) {
            SearchOutcome.Failure(
                if (e is SocketTimeoutException) SearchError.Timeout else SearchError.NetworkUnavailable,
            )
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
        val results = buildList {
            val array = JSONObject(body).optJSONObject("web")?.optJSONArray("results")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val url = entry.optString("url")
                    if (url.isEmpty()) continue
                    add(
                        SearchResult(
                            title = entry.optString("title"),
                            url = url,
                            snippet = entry.optString("description"),
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

    private companion object {
        const val ENDPOINT = "https://api.search.brave.com/res/v1/web/search"
    }
}
