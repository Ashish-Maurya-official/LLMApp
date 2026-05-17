package com.example.llmapp.core.search.classification

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.rules.*

/**
 * High-performance score evaluation engine mapping queries to search types.
 */
object SearchScorer {

    data class ScoreResult(
        val type: SearchType,
        val score: Float,
        val confidence: Float,
        val reason: SearchReason
    )

    private val RULES = listOf(
        WeatherRules.rule,
        NewsRules.rule,
        SportsRules.rule,
        FinanceRules.rule,
        ProductRules.rule,
        LocalRules.rule,
        TechnicalRules.rule,
        DeepResearchRules.rule
    )

    /**
     * Scores the query against the rules registry and selects the highest scoring classification.
     */
    fun score(query: String): ScoreResult {
        val lowerText = query.lowercase().trim()
        val words = lowerText.split("\\s+".toRegex()).map { it.replace(Regex("[?\\!.,]"), "") }.filter { it.isNotBlank() }

        var bestType = SearchType.NONE
        var bestScore = 0f
        var bestConfidence = 0f
        var bestReason = SearchReason.NONE

        // 1. Iterate through all rules to compute weighted scores
        for (rule in RULES) {
            val hits = rule.keywords.count { keyword ->
                if (keyword.contains(" ")) {
                    lowerText.contains(keyword)
                } else {
                    words.contains(keyword)
                }
            }

            if (hits > 0) {
                var ruleConfidence = rule.confidence
                var calculatedScore = hits * ruleConfidence

                // Modifiers: Suppress static technical queries to prevent unnecessary web searches
                if (rule.type == SearchType.TECHNICAL_DOCS) {
                    val isLiveRelated = lowerText.contains("latest") || lowerText.contains("new version") || 
                            lowerText.contains("release") || lowerText.contains("today") || 
                            lowerText.contains("2025") || lowerText.contains("2026")
                    if (!isLiveRelated) {
                        calculatedScore = 0f
                        ruleConfidence = 0f
                    }
                }

                if (calculatedScore > bestScore) {
                    bestScore = calculatedScore
                    bestType = rule.type
                    bestConfidence = ruleConfidence
                    bestReason = mapToReason(rule.type)
                }
            }
        }

        // 2. Special fallback case: temporal and general news/real-time triggers
        if (bestType == SearchType.NONE) {
            val liveTriggers = setOf(
                "current", "who is the prime minister", "who is the president", "live score", "latest news", "latest updates",
                "today", "now", "right now", "this week", "who won", "2025", "2026"
            )
            val hasLiveTrigger = liveTriggers.any { trigger ->
                if (trigger.contains(" ")) lowerText.contains(trigger) else words.contains(trigger)
            }
            if (hasLiveTrigger) {
                bestType = SearchType.NEWS
                bestScore = 0.85f
                bestConfidence = 0.85f
                bestReason = SearchReason.REALTIME_INFORMATION
            }
        }

        return ScoreResult(bestType, bestScore, bestConfidence, bestReason)
    }

    private fun mapToReason(type: SearchType): SearchReason {
        return when (type) {
            SearchType.WEATHER -> SearchReason.WEATHER_DATA
            SearchType.NEWS -> SearchReason.NEWS_EVENT
            SearchType.SPORTS -> SearchReason.LIVE_SCORES
            SearchType.FINANCE -> SearchReason.MARKET_DATA
            SearchType.PRODUCT -> SearchReason.REALTIME_INFORMATION
            SearchType.LOCAL -> SearchReason.LOCATION_DATA
            SearchType.TECHNICAL_DOCS -> SearchReason.TECHNICAL_DOCS_LIVE
            SearchType.DEEP_RESEARCH -> SearchReason.REALTIME_INFORMATION
            else -> SearchReason.NONE
        }
    }
}
