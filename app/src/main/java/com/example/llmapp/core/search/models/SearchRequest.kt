package com.example.llmapp.core.search.models

/**
 * Encapsulates a search request with all configuration needed by a provider.
 */
data class SearchRequest(
    val query: String,
    val maxResults: Int = 3,
    val providerOverride: String? = null
)
