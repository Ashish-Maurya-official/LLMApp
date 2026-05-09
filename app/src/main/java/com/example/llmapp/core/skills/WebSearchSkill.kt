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

            val bodies = doc.select(".result__body").take(3)
            
            if (bodies.isEmpty()) {
                val error = "No relevant search results found for: $query"
                return Pair(error, error)
            }

            val llmSnippets = mutableListOf<String>()
            val uiLinks = mutableListOf<String>()

            for ((i, body) in bodies.withIndex()) {
                val title = body.select(".result__title > a").text()
                val linkUrl = body.select("a.result__url").attr("href")
                val text = body.select("a.result__snippet").text()
                
                llmSnippets.add("${i + 1}. Source: $title\nContent: $text")
                uiLinks.add("- [$title]($linkUrl)")
            }
            
            val uiText = "🔍 Web Search Sources:\n" + uiLinks.joinToString("\n")
            val llmText = "Web Search Results for '$query':\n" + llmSnippets.joinToString("\n\n")
            
            Pair(uiText, llmText)
        } catch (e: Exception) {
            val error = "Error performing web search: ${e.message}"
            Pair(error, error)
        }
    }
}
