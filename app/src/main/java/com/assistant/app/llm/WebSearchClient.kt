package com.assistant.app.llm

import com.assistant.app.llm.model.SearchError
import com.assistant.app.llm.model.SearchOutcome
import com.assistant.app.llm.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Unified web search client combining provider search, page fetching, and content extraction.
 * Powered by open SearXNG metasearch with configurable endpoints and zero paid API keys required.
 */
class WebSearchClient(
    private val provider: WebSearchProvider,
    private val pageFetcher: PageFetcher,
    private val contentExtractor: ContentExtractor,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxPagesToFetch: Int = DEFAULT_MAX_PAGES_TO_FETCH,
) {
    /**
     * Primary convenience constructor for standard OkHttp client and SearXNG endpoint.
     */
    constructor(
        client: OkHttpClient,
        endpoint: String = SearXNGSearchProvider.DEFAULT_ENDPOINT,
        apiKey: String? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        maxPagesToFetch: Int = DEFAULT_MAX_PAGES_TO_FETCH,
    ) : this(
        provider = SearXNGSearchProvider(client, endpoint, apiKey, dispatcher),
        pageFetcher = HttpPageFetcher(client, dispatcher),
        contentExtractor = JsoupContentExtractor(),
        dispatcher = dispatcher,
        maxPagesToFetch = maxPagesToFetch,
    )

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
