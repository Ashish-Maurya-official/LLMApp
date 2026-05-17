package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated technical docs classification rules.
 */
object TechnicalRules {
    val rule = SearchRule(
        type = SearchType.TECHNICAL_DOCS,
        keywords = setOf(
            "how to", "code", "docs", "documentation", "api", "compile", "error",
            "exception", "dependency", "gradle", "kotlin", "python", "github"
        ),
        confidence = 0.8f,
        description = "Matches developer APIs, gradle errors, scripting, and code structures."
    )
}
