package com.assistant.app.llm

import com.assistant.app.llm.model.SearchOutcome

interface WebSearchProvider {
    suspend fun search(query: String, maxResults: Int = 5): SearchOutcome
}
