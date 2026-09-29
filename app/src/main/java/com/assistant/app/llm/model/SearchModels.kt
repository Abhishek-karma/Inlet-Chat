package com.assistant.app.llm.model

import org.json.JSONArray
import org.json.JSONObject

/** One web search result shown to the model as context. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val engine: String? = null,
)

/** Extracted readable page text and title. */
data class ExtractedContent(
    val title: String,
    val text: String,
)

sealed interface SearchOutcome {
    data class Success(val results: List<SearchResult>) : SearchOutcome

    /**
     * A search failure. [error] carries the user-facing message
     * ([SearchError.userMessage]); [detail] is optional diagnostics and is
     * deliberately not rendered in the UI.
     */
    data class Failure(val error: SearchError, val detail: String? = null) : SearchOutcome
}

enum class SearchError(val userMessage: String) {
    RateLimited("Search rate limit reached. Try again shortly."),
    NetworkUnavailable("Unable to connect to search service."),
    Timeout("Search request timed out."),
    InvalidResponse("Search service returned an invalid response."),
    NoResults("No relevant web results found."),
    Unknown("Something went wrong with search."),
}

/** Serializes the sources persisted under an answer. */
fun List<SearchResult>.toSearchJson(): String = JSONArray().apply {
    forEach { result ->
        put(
            JSONObject()
                .put("title", result.title)
                .put("url", result.url)
                .put("snippet", result.snippet),
        )
    }
}.toString()

/** Restores persisted sources; entries without a URL are skipped. */
fun searchResultsFromJson(raw: String?): List<SearchResult> =
    raw?.let { json ->
        runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val result = SearchResult(
                    title = obj.optString("title"),
                    url = obj.optString("url"),
                    snippet = obj.optString("snippet"),
                )
                if (result.url.isBlank()) null else result
            }
        }.getOrDefault(emptyList())
    }.orEmpty()
