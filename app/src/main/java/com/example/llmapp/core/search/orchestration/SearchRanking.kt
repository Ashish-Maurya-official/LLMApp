package com.example.llmapp.core.search.orchestration

import com.example.llmapp.core.search.models.SearchResult

/**
 * Re-ranks search results by relevance to the original query.
 * Uses word-overlap scoring — no ML model needed, zero latency.
 */
object SearchRanking {

    /**
     * Ranks [results] by how many query words appear in the title + snippet.
     * Higher score = more relevant = earlier in list.
     */
    fun rank(query: String, results: List<SearchResult>): List<SearchResult> {
        if (results.size <= 1) return results

        val queryWords = query.lowercase()
            .split(Regex("[\\s\\-_.,!?]+"))
            .filter { it.length > 2 }
            .toSet()

        return results.sortedByDescending { result ->
            val titleLower = result.title.lowercase()
            val snippetLower = result.snippet.lowercase()
            queryWords.sumOf { word ->
                val titleHit = if (titleLower.contains(word)) 2 else 0 // title hits worth double
                val snippetHit = if (snippetLower.contains(word)) 1 else 0
                titleHit + snippetHit
            }
        }
    }

    /**
     * Formats ranked results into a compact, LLM-ready string.
     * Enforces hard limits: max 3 results, 300 chars per snippet, 1200 chars total.
     */
    fun formatForLlm(query: String, results: List<SearchResult>, maxResults: Int = 3): String {
        val ranked = rank(query, results).take(maxResults)
        val sb = StringBuilder()
        var totalChars = 0
        val maxTotal = 1200

        sb.append("Web Search Results for \"$query\":\n\n")
        for ((i, result) in ranked.withIndex()) {
            val snippet = result.snippet.take(300)
            val entry = "${i + 1}. ${result.title}\nURL: ${result.url}\n$snippet\n"
            if (totalChars + entry.length > maxTotal) break
            sb.append(entry)
            sb.append("\n")
            totalChars += entry.length
        }
        return sb.toString().trim()
    }
}
