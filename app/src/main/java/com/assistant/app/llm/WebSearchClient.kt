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
import org.jsoup.Jsoup
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Keyless web search over DuckDuckGo's no-JavaScript HTML endpoint.
 *
 * The endpoint needs no account, instance, or API key, so search is available
 * as soon as the app is installed.
 */
class DuckDuckGoSearchProvider(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val endpoint: String = ENDPOINT,
) : WebSearchProvider {

    override suspend fun search(query: String, maxResults: Int): SearchOutcome = withContext(dispatcher) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext SearchOutcome.Failure(SearchError.NoResults)
        }

        val url = endpoint.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", trimmed)
            ?.addQueryParameter("kl", "us-en")
            ?.build()
            ?: return@withContext SearchOutcome.Failure(SearchError.Unknown)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://duckduckgo.com/")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                // The endpoint answers rate limiting with 202 and a body that
                // carries no results, which is not a transport failure.
                if (response.code == 202) {
                    return@withContext SearchOutcome.Failure(SearchError.RateLimited)
                }
                if (!response.isSuccessful) {
                    return@withContext SearchOutcome.Failure(
                        if (response.code == 429) SearchError.RateLimited else SearchError.Unknown,
                    )
                }
                val body = response.body?.source()
                    ?: return@withContext SearchOutcome.Failure(SearchError.InvalidResponse)
                body.request(MAX_RESPONSE_BYTES + 1)
                if (body.buffer.size > MAX_RESPONSE_BYTES) {
                    return@withContext SearchOutcome.Failure(SearchError.InvalidResponse)
                }
                val html = body.readUtf8()
                if (html.contains("anomaly", ignoreCase = true) ||
                    html.contains("challenge", ignoreCase = true)
                ) {
                    return@withContext SearchOutcome.Failure(SearchError.RateLimited)
                }
                val results = parseResults(html, maxResults)
                if (results.isEmpty()) {
                    SearchOutcome.Failure(SearchError.NoResults)
                } else {
                    SearchOutcome.Success(results)
                }
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

    private fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        val document = Jsoup.parse(html, endpoint)
        val seen = mutableSetOf<String>()
        val results = mutableListOf<SearchResult>()

        for (element in document.select("div.result, div.web-result")) {
            if (results.size >= maxResults) break
            val anchor = element.selectFirst("a.result__a") ?: continue
            val href = anchor.attr("href")
            val target = unwrapRedirect(href) ?: continue
            if (!target.startsWith("http://") && !target.startsWith("https://")) continue

            val normalized = target.trimEnd('/').lowercase()
            if (!seen.add(normalized)) continue

            val title = anchor.text().trim().ifEmpty { target }
            val snippet = element.selectFirst(".result__snippet")
                ?.text()
                ?.trim()
                .orEmpty()

            results += SearchResult(
                title = title,
                url = target,
                snippet = snippet,
                engine = "duckduckgo",
            )
        }
        return results
    }

    /**
     * DuckDuckGo wraps result links as `//duckduckgo.com/l/?uddg=<encoded>`.
     * The real destination is the decoded [uddg] parameter.
     */
    private fun unwrapRedirect(href: String): String? {
        if (href.isBlank()) return null
        val query = href.substringAfter('?', "")
        if (query.isEmpty()) return href.takeIf { it.startsWith("http") }
        val encoded = query.split('&')
            .firstOrNull { it.startsWith("uddg=") }
            ?.removePrefix("uddg=")
            ?: return href.takeIf { it.startsWith("http") }
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }
            .getOrNull()
            ?.takeIf { it.startsWith("http") }
    }

    companion object {
        const val ENDPOINT = "https://html.duckduckgo.com/html/"
        private const val MAX_RESPONSE_BYTES = 1024L * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    }
}

/**
 * Runs a web search and enriches the top results with clean article text
 * fetched from the result pages.
 */
class WebSearchClient(
    private val provider: WebSearchProvider,
    private val pageFetcher: PageFetcher,
    private val contentExtractor: ContentExtractor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxPagesToFetch: Int = DEFAULT_MAX_PAGES_TO_FETCH,
) {
    suspend fun search(query: String, maxResults: Int = 5): SearchOutcome = withContext(dispatcher) {
        val searchOutcome = try {
            provider.search(query, maxResults)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SearchOutcome.Failure(SearchError.Unknown)
        }

        if (searchOutcome !is SearchOutcome.Success) {
            return@withContext searchOutcome
        }

        val results = searchOutcome.results
        if (results.isEmpty()) {
            return@withContext SearchOutcome.Failure(SearchError.NoResults)
        }

        // Fetch top pages to enrich snippets with clean readable article text
        val enrichedResults = results.mapIndexed { index, result ->
            if (index < maxPagesToFetch && (result.url.startsWith("https://") || result.url.startsWith("http://"))) {
                try {
                    val html = pageFetcher.fetch(result.url)
                    if (!html.isNullOrBlank()) {
                        val extracted = contentExtractor.extract(html, result.url)
                        if (extracted.text.isNotBlank() && extracted.text.length >= 40) {
                            val title = if (result.title.isBlank() || result.title == result.url) {
                                extracted.title.ifBlank { result.title }
                            } else {
                                result.title
                            }
                            result.copy(
                                title = title,
                                snippet = extracted.text,
                            )
                        } else {
                            result
                        }
                    } else {
                        result
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    result
                }
            } else {
                result
            }
        }

        SearchOutcome.Success(enrichedResults)
    }

    companion object {
        const val DEFAULT_MAX_PAGES_TO_FETCH = 2
    }
}
