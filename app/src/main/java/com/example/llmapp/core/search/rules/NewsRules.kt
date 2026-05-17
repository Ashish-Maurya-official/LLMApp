package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated news queries classification rules.
 */
object NewsRules {
    val rule = SearchRule(
        type = SearchType.NEWS,
        keywords = setOf(
            "news", "latest news", "breaking news", "current events", "today",
            "yesterday", "recently", "recent", "update", "developments"
        ),
        confidence = 0.75f,
        description = "Triggers current events and general daily news."
    )
}
