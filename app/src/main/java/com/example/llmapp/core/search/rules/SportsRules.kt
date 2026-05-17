package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated sports inquiries classification rules.
 */
object SportsRules {
    val rule = SearchRule(
        type = SearchType.SPORTS,
        keywords = setOf(
            "score", "match", "ipl", "cricket", "football", "basketball", "tennis",
            "soccer", "game", "league", "vs", "cup", "tournament"
        ),
        confidence = 0.9f,
        description = "Matches live score updates, standings, and tournaments."
    )
}
