package com.example.llmapp.core.search.models

/**
 * Encapsulates a normalized response from any search provider.
 */
data class SearchResponse(
    val results: List<SearchResult> = emptyList(),
    val providerUsed: String = "",
    val fromCache: Boolean = false,
    val durationMs: Long = 0,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null && results.isNotEmpty()
}
