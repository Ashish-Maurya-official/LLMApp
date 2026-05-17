package com.example.llmapp.core.search.models

/**
 * Normalized search result from any provider.
 */
data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val source: String = ""
)
