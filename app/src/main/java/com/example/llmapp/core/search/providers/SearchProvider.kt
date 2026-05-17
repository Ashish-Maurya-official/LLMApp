package com.example.llmapp.core.search.providers

import com.example.llmapp.core.search.models.SearchRequest
import com.example.llmapp.core.search.models.SearchResponse

/**
 * Abstraction layer for web search providers.
 *
 * Implement this interface to add future providers:
 * Brave Search, Tavily, Bing, SearXNG, Perplexity, custom APIs.
 */
interface SearchProvider {

    /** Human-readable provider name, e.g. "Google Custom Search" */
    val providerName: String

    /**
     * Returns true if the user has supplied all required credentials/configuration.
     * Does NOT validate whether credentials are valid — use [validateConfiguration] for that.
     */
    fun isConfigured(): Boolean

    /**
     * Performs a lightweight API call to verify credentials are valid and quota is available.
     * Returns a human-readable status string, e.g. "✓ Connected Successfully" or "✕ Invalid API Key".
     */
    suspend fun validateConfiguration(): String

    /**
     * Executes a search and returns a normalized [SearchResponse].
     * Must run on a background dispatcher — never blocks the main thread.
     */
    suspend fun search(request: SearchRequest): SearchResponse
}
