package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated deep research queries classification rules.
 */
object DeepResearchRules {
    val rule = SearchRule(
        type = SearchType.DEEP_RESEARCH,
        keywords = setOf(
            "deep research", "research about", "comprehensive analysis",
            "detailed report", "literature review", "in-depth study"
        ),
        confidence = 0.9f,
        description = "Triggers multi-step exhaustive internet research and reports."
    )
}
