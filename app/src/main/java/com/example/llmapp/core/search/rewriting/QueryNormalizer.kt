package com.example.llmapp.core.search.rewriting

/**
 * Normalizes user queries by removing conversational filler words, transitions, and punctuation.
 */
object QueryNormalizer {

    private val QUERY_CLEANUP_PATTERNS = listOf(
        Regex("(?i)\\bwhat is\\b"),
        Regex("(?i)\\btell me\\b"),
        Regex("(?i)\\bcan you\\b"),
        Regex("(?i)\\bplease search for\\b"),
        Regex("(?i)\\blook up\\b"),
        Regex("(?i)\\bwho is\\b"),
        Regex("(?i)\\bwhere is\\b"),
        Regex("(?i)\\bhow about\\b"),
        Regex("(?i)\\bshow me\\b"),
        Regex("(?i)\\bsearch for\\b")
    )

    fun cleanQuery(rawText: String): String {
        var clean = rawText.trim()
        QUERY_CLEANUP_PATTERNS.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        
        // Remove question marks, punctuation, and extra whitespace
        clean = clean.replace(Regex("[?\\!.,]"), "").replace(Regex("\\s+"), " ").trim()
        
        return clean.ifBlank { rawText }
    }

    fun stripTransitions(text: String): String {
        var clean = text.trim()
        val transitionPatterns = listOf(
            Regex("(?i)^and\\b"),
            Regex("(?i)^what about\\b"),
            Regex("(?i)^how about\\b"),
            Regex("(?i)^or\\b"),
            Regex("(?i)^but\\b")
        )
        transitionPatterns.forEach { pattern ->
            clean = pattern.replace(clean, "")
        }
        return clean.replace(Regex("[?\\!.,]"), "").replace(Regex("\\s+"), " ").trim()
    }
}
