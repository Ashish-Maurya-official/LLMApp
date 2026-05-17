package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated product-related search intent classification rules.
 */
object ProductRules {
    val rule = SearchRule(
        type = SearchType.PRODUCT,
        keywords = setOf(
            "buy", "specs", "specifications", "price of", "release date",
            "availability", "features of", "iphone", "pixel 9", "review of"
        ),
        confidence = 0.85f,
        description = "Matches hardware specs, price listings, reviews, and purchases."
    )
}
