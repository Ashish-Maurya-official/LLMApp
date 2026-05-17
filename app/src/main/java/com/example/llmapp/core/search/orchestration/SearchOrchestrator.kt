package com.example.llmapp.core.search.orchestration

import android.util.Log
import com.example.llmapp.core.search.models.SearchRequest
import com.example.llmapp.core.search.models.SearchResponse
import com.example.llmapp.core.search.providers.SearchProviderFactory
import com.example.llmapp.core.search.settings.SearchPreferences
import com.example.llmapp.core.search.settings.SecureSearchStorage
import com.example.llmapp.core.search.extraction.WebPageFetcher
import com.example.llmapp.core.search.extraction.ContentExtractor
import com.example.llmapp.core.search.extraction.CompressionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Central orchestrator for the web search pipeline.
 *
 * Full flow:
 *  Query → SearchClassifier → Cache → Provider → Fallback → Ranking → Formatted Context
 *
 * Never blocks the main thread. All work runs on [Dispatchers.IO].
 */
class SearchOrchestrator(
    private val preferences: SearchPreferences,
    private val secureStorage: SecureSearchStorage
) {

    private val cache = SearchCache()

    /**
     * Determines if the given [query] needs a web search and performs it if so.
     *
     * @param query           The raw user query.
     * @param forceSearch     Skip classifier and force execution (used when sentinel detected).
     * @return A [SearchResult] containing formatted context for the LLM, or null if skipped.
     */
    suspend fun resolveSearch(
        query: String,
        forceSearch: Boolean = false
    ): OrchestratorResult = withContext(Dispatchers.IO) {
        try {
            if (!preferences.webSearchEnabled) {
                Log.d("SearchOrchestrator", "Web search disabled by user.")
                return@withContext OrchestratorResult.Skipped("Web search disabled")
            }

            // Direct URL crawling check: if the query itself is a URL, crawl it directly!
            val trimmedQuery = query.trim()
            val isUrlQuery = trimmedQuery.startsWith("http://", ignoreCase = true) || trimmedQuery.startsWith("https://", ignoreCase = true)
            if (isUrlQuery) {
                Log.d("SearchOrchestrator", "Direct URL crawling requested: $trimmedQuery")
                val html = WebPageFetcher.fetchHtml(trimmedQuery)
                if (html != null) {
                    val extractedText = ContentExtractor.extractArticle(html)
                    val compressedText = CompressionPipeline.compress(extractedText)
                    if (compressedText.isNotBlank()) {
                        val formattedContext = "=== EXTRACTED WEBPAGE CONTENT ===\nSource: $trimmedQuery\n\n$compressedText"
                        return@withContext OrchestratorResult.Success(
                            formattedContext = formattedContext,
                            response = SearchResponse(
                                results = listOf(
                                    com.example.llmapp.core.search.models.SearchResult(
                                        title = "Direct Read: $trimmedQuery",
                                        snippet = compressedText.take(300),
                                        url = trimmedQuery,
                                        source = trimmedQuery
                                    )
                                ),
                                providerUsed = "Direct Web Crawler"
                            ),
                            statusMessage = "Deep-reading page: $trimmedQuery"
                        )
                    }
                }
                Log.w("SearchOrchestrator", "Direct crawling failed for URL: $trimmedQuery. Falling back to search.")
            }

            // 1. Classify (skip if sentinel forced the search)
            val decision: SearchDecision = if (forceSearch) {
                SearchDecision(needed = true, confidence = 1.0f, rewrittenQuery = query.ifBlank { "general search" })
            } else {
                SearchClassifier.classify(query)
            }

            if (!decision.needed) {
                Log.d("SearchOrchestrator", "Classifier skipped search (confidence=${decision.confidence})")
                return@withContext OrchestratorResult.Skipped("Not a real-time query")
            }

            val effectiveQuery = decision.rewrittenQuery.ifBlank { query }.take(200) // guard blank/overlong queries
            Log.d("SearchOrchestrator", "Search needed. Query: \"$effectiveQuery\" (confidence=${decision.confidence})")

            // 2. Cache check
            val cached = cache.get(effectiveQuery)
            if (cached != null) {
                Log.d("SearchOrchestrator", "Cache hit for: \"$effectiveQuery\"")
                val formatted = SearchRanking.formatForLlm(effectiveQuery, cached.results, preferences.maxResults)
                return@withContext OrchestratorResult.Success(
                    formattedContext = formatted,
                    response = cached,
                    statusMessage = "Using cached results"
                )
            }

            // 3. Primary provider
            val request = com.example.llmapp.core.search.models.SearchRequest(
                query = effectiveQuery,
                maxResults = preferences.maxResults.coerceIn(1, 5)
            )
            val primaryProvider = SearchProviderFactory.create(preferences, secureStorage)

            // Guard: if primary is Google but not configured, skip straight to DDG
            var response = if (!primaryProvider.isConfigured()) {
                Log.w("SearchOrchestrator", "${primaryProvider.providerName} not configured. Using DuckDuckGo.")
                SearchProviderFactory.createFallback().search(request)
            } else {
                primaryProvider.search(request)
            }

            // 4. Fallback if primary failed and primary wasn't already DDG
            if (!response.isSuccess && primaryProvider.providerName != "DuckDuckGo") {
                Log.w("SearchOrchestrator", "Primary provider failed: ${response.error}. Falling back to DuckDuckGo.")
                response = SearchProviderFactory.createFallback().search(request)
            }

            // 5a. Hard fail — all providers failed (network down, etc.)
            if (response.error != null && response.results.isEmpty()) {
                Log.e("SearchOrchestrator", "All providers failed: ${response.error}")
                return@withContext OrchestratorResult.Failed(response.error)
            }

            // 5b. Soft fail — providers succeeded but no results for this query
            if (response.results.isEmpty()) {
                Log.w("SearchOrchestrator", "Search returned no results for: \"$effectiveQuery\"")
                return@withContext OrchestratorResult.NoResults(effectiveQuery)
            }

            // 6. Cache successful result
            cache.put(effectiveQuery, response)

            // 7. Progressive Retrieval: Deep Browse Extraction
            var finalFormatted = SearchRanking.formatForLlm(effectiveQuery, response.results, preferences.maxResults)
            var finalStatusMsg = if (response.providerUsed == "DuckDuckGo" && primaryProvider.providerName != "DuckDuckGo") {
                "Searched via DuckDuckGo (fallback)"
            } else {
                "Searched via ${response.providerUsed}"
            }

            val mode = preferences.webRetrievalMode
            val isDeepIntent = decision.intent == com.example.llmapp.core.search.orchestration.SearchIntent.DEEP
            val shouldDeepBrowse = mode == com.example.llmapp.core.search.settings.WebRetrievalMode.DEEP || 
                                   (mode == com.example.llmapp.core.search.settings.WebRetrievalMode.BALANCED && isDeepIntent)

            if (shouldDeepBrowse && response.results.isNotEmpty()) {
                Log.d("SearchOrchestrator", "Progressive Retrieval triggered: Fetching top webpage...")
                val topUrl = response.results.first().url
                val html = com.example.llmapp.core.search.extraction.WebPageFetcher.fetchHtml(topUrl)
                if (html != null) {
                    val extractedText = com.example.llmapp.core.search.extraction.ContentExtractor.extractArticle(html)
                    val compressedText = com.example.llmapp.core.search.extraction.CompressionPipeline.compress(extractedText)
                    if (compressedText.isNotBlank()) {
                        finalFormatted += "\n\n=== EXTRACTED WEBPAGE CONTENT ===\n"
                        finalFormatted += "Source: $topUrl\n"
                        finalFormatted += compressedText
                        finalStatusMsg = "Reading article: ${response.results.first().title}"
                    }
                }
            }

            OrchestratorResult.Success(
                formattedContext = finalFormatted,
                response = response,
                statusMessage = finalStatusMsg
            )
        } catch (e: Exception) {
            // Last-resort catch — ensures the chat never crashes due to search
            Log.e("SearchOrchestrator", "Unexpected error during search: ${e.javaClass.simpleName}: ${e.message}")
            OrchestratorResult.Failed("Unexpected error: ${e.javaClass.simpleName}")
        }
    }

    fun clearCache() = cache.clear()
}

/** Typed result returned by the orchestrator. */
sealed class OrchestratorResult {
    data class Success(
        val formattedContext: String,
        val response: SearchResponse,
        val statusMessage: String
    ) : OrchestratorResult()

    /** Search ran but returned zero results for this query (not a provider failure). */
    data class NoResults(val query: String) : OrchestratorResult()

    data class Skipped(val reason: String) : OrchestratorResult()
    data class Failed(val error: String) : OrchestratorResult()
}
