package com.example.llmapp.core.skills

import org.jsoup.Jsoup
import java.net.URLEncoder

class WebSearchSkill : Skill {
    override val name = "WebSearch"
    override val description = "Searches the internet via DuckDuckGo Lite. Arguments: query (String)."

    override fun execute(args: Map<String, Any>): String {
        val query = args["query"] as? String ?: return "Error: Missing query."
        return search(query).second
    }

    fun search(query: String): Pair<String, String> {
        return searchInternet(query)
    }

    private fun searchInternet(query: String): Pair<String, String> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://html.duckduckgo.com/html/"
            val doc = Jsoup.connect(url)
                .data("q", encodedQuery)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .post()

            // Fix 3: Fetch 5 results instead of 3 for richer context
            val bodies = doc.select(".result__body").take(5)

            if (bodies.isEmpty()) {
                val error = "No relevant search results found for: $query"
                return Pair(error, error)
            }

            // Fix 5: Score and sort snippets — put the most relevant results first
            // Relevance score = number of query words found in the title (case-insensitive)
            val queryWords = query.lowercase().split(" ", "-", "_").filter { it.length > 2 }.toSet()

            data class ScoredResult(val title: String, val url: String, val snippet: String, val score: Int)

            val scored = bodies.map { body ->
                val title = body.select(".result__title > a").text()
                val linkUrl = body.select("a.result__url").attr("href")
                val text = body.select("a.result__snippet").text()
                val score = queryWords.count { word -> title.lowercase().contains(word) }
                ScoredResult(title, linkUrl, text, score)
            }.sortedByDescending { it.score }  // highest relevance first

            val llmSnippets = mutableListOf<String>()
            val uiLinks = mutableListOf<String>()

            for ((i, result) in scored.withIndex()) {
                // Include title + full snippet text for better LLM context
                llmSnippets.add("${i + 1}. [${result.title}]\n${result.snippet}")
                uiLinks.add("- [${result.title}](${result.url})")
            }

            val uiText = "🔍 Web Search Sources:\n" + uiLinks.joinToString("\n")
            val llmText = "Web Search Results for '$query':\n\n" + llmSnippets.joinToString("\n\n")

            Pair(uiText, llmText)
        } catch (e: Exception) {
            val error = "Error performing web search: ${e.message}"
            Pair(error, error)
        }
    }
}
