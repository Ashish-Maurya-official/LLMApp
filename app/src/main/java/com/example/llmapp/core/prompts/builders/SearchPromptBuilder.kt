package com.example.llmapp.core.prompts.builders

import com.example.llmapp.core.search.SearchType
import java.util.Locale

/**
 * Generates highly specialized prompts for search response turns.
 * Tailors authoritative blending guidelines and constraints based on the specific SearchType.
 */
object SearchPromptBuilder {

    fun build(
        userQuestion: String,
        searchContext: String,
        type: SearchType
    ): String {
        val sb = StringBuilder()
        
        sb.append("SYSTEM: A real-time web search has already been completed.\n")
        sb.append("\n=== LIVE SEARCH DATA START ===\n")
        sb.append(searchContext)
        sb.append("\n=== LIVE SEARCH DATA END ===\n\n")
        
        sb.append("CRITICAL INSTRUCTIONS FOR MODEL RESPONSE:\n")
        sb.append("  - Blend the live search data above with your internal knowledge to answer the user's question directly, naturally, and authoritatively.\n")
        sb.append("  - DO NOT say 'I do not have real-time access' or 'I cannot browse' — you have the live data above.\n")
        sb.append("  - DO NOT tell the user to 'visit the website', 'check the link', or 'go to the official site' to find information. You must provide the answer yourself using the live data.\n")
        sb.append("  - DO NOT use meta-phrases like 'based on the search results', 'the provided snippets show', 'according to the search', or 'live search data shows'. Just state the facts directly as absolute truth.\n")
        sb.append("  - CRITICAL CRAWL RULE: If the snippets above DO NOT contain the exact factual answer but contain a highly relevant webpage URL, you MUST IMMEDIATELY trigger a progressive deep web crawl by outputting the specific URL inside a `[SEARCH_NEEDED: <url>]` command (e.g. `[SEARCH_NEEDED: https://www.pmindia.gov.in/en/]`) on its own line. Do NOT explain what is missing to the user, and do NOT ask for permission — crawl it yourself first!\n")
        sb.append("  - If no URL is present or you are completely unable to find the answer even after deep crawling, only then state clearly that the exact real-time data couldn't be found.\n")
        
        // Specialized domain tailoring
        when (type) {
            SearchType.NEWS -> {
                sb.append("  - NEWS RULE: Prioritize freshness, list dates clearly, and outline multi-source perspectives if there's an ongoing live development.\n")
            }
            SearchType.SPORTS -> {
                sb.append("  - SPORTS RULE: Detail direct scores, tournament tables, player statistics, and match outcome breakdowns using clean tables.\n")
            }
            SearchType.FINANCE -> {
                sb.append("  - FINANCE RULE: Present exact numeric values, ticker names, and percentages clearly. Add a subtle disclaimer that market values fluctuate rapidly.\n")
            }
            SearchType.PRODUCT -> {
                sb.append("  - PRODUCT SPECS RULE: Build clear specifications comparison tables comparing prices, versions, and main product features.\n")
            }
            SearchType.LOCAL -> {
                sb.append("  - LOCAL SEARCH RULE: Specify addresses, distance, coordinates, and hours of operation clearly whenever available.\n")
            }
            SearchType.TECHNICAL_DOCS -> {
                sb.append("  - TECHNICAL DOCS RULE: Format code samples cleanly using standard code fences (e.g. ```kotlin). Focus on framework versions and official API compatibility.\n")
            }
            SearchType.DEEP_RESEARCH -> {
                sb.append("  - DEEP RESEARCH RULE: Provide a highly detailed, comprehensive synthesis of the topic. Group content with clear thematic headers and bullet lists.\n")
            }
            else -> {}
        }
        
        return sb.toString()
    }
}
