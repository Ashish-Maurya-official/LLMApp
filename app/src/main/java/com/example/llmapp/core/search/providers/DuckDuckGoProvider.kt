package com.example.llmapp.core.search.providers

import android.util.Log
import com.example.llmapp.core.search.models.SearchRequest
import com.example.llmapp.core.search.models.SearchResponse
import com.example.llmapp.core.search.models.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * DuckDuckGo Lite HTML scraper, normalized into the [SearchProvider] interface.
 * No API key required — used as a free fallback provider.
 */
class DuckDuckGoProvider : SearchProvider {

    override val providerName = "DuckDuckGo"

    override fun isConfigured() = true // No credentials needed

    override suspend fun validateConfiguration() = "✓ DuckDuckGo (always available — no key required)"

    override suspend fun search(request: SearchRequest): SearchResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val encodedQuery = URLEncoder.encode(request.query, "UTF-8")
            val doc = Jsoup.connect("https://html.duckduckgo.com/html/")
                .data("q", encodedQuery)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .timeout(8000)
                .post()

            val bodies = doc.select(".result__body").take(request.maxResults.coerceIn(1, 5))

            if (bodies.isEmpty()) {
                return@withContext SearchResponse(
                    error = "No results found",
                    providerUsed = providerName,
                    durationMs = System.currentTimeMillis() - start
                )
            }

            val queryWords = request.query.lowercase().split(" ", "-", "_")
                .filter { it.length > 2 }.toSet()

            val results = bodies.map { body ->
                val title = body.select(".result__title > a").text()
                val url = body.select("a.result__url").attr("href")
                val snippet = body.select("a.result__snippet").text().take(300)
                val score = queryWords.count { word -> title.lowercase().contains(word) }
                Triple(score, title, SearchResult(title = title, snippet = snippet, url = url, source = "duckduckgo.com"))
            }.sortedByDescending { it.first }.map { it.third }

            SearchResponse(
                results = results,
                providerUsed = providerName,
                durationMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.e("DuckDuckGoProvider", "Search failed: ${e.message}")
            SearchResponse(
                error = e.message,
                providerUsed = providerName,
                durationMs = System.currentTimeMillis() - start
            )
        }
    }
}
