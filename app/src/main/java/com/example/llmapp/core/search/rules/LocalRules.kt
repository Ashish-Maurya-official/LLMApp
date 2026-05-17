package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated local geo-centric query classification rules.
 */
object LocalRules {
    val rule = SearchRule(
        type = SearchType.LOCAL,
        keywords = setOf(
            "near me", "restaurants in", "hotels in", "stores in", "directions to",
            "map of", "weather in"
        ),
        confidence = 0.85f,
        description = "Matches local restaurants, maps, locations, and travel."
    )
}
