package com.assistant.app.data.search

import com.assistant.app.llm.model.SearchError
import com.assistant.app.llm.model.SearchOutcome
import com.assistant.app.llm.model.SearchResult
import kotlinx.coroutines.CancellationException

data class SearchExecutionResult(
    val results: List<SearchResult>,
    val noticeMessage: String?
)

/**
 * Handles executing web search queries, error normalization, and user notice generation.
 */
class SearchController(
    private val webSearch: (suspend (String) -> SearchOutcome?)? = null
) {
    suspend fun executeSearch(query: String): SearchExecutionResult {
        val search = webSearch ?: return SearchExecutionResult(
            results = emptyList(),
            noticeMessage = SEARCH_NOT_CONFIGURED
        )
        val outcome = try {
            search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SearchExecutionResult(
                results = emptyList(),
                noticeMessage = SearchError.Unknown.userMessage
            )
        }
        return when (outcome) {
            null -> SearchExecutionResult(
                results = emptyList(),
                noticeMessage = SEARCH_NOT_CONFIGURED
            )
            is SearchOutcome.Success -> {
                if (outcome.results.isEmpty()) {
                    SearchExecutionResult(
                        results = emptyList(),
                        noticeMessage = SearchError.NoResults.userMessage
                    )
                } else {
                    SearchExecutionResult(
                        results = outcome.results,
                        noticeMessage = null
                    )
                }
            }
            is SearchOutcome.Failure -> SearchExecutionResult(
                results = emptyList(),
                noticeMessage = outcome.error.userMessage
            )
        }
    }

    companion object {
        const val SEARCH_NOT_CONFIGURED = "Configure a search service in Settings first."
    }
}
