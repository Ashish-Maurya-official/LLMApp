package com.example.llmapp.core.search.classification

import com.example.llmapp.core.search.SearchType

/**
 * Encapsulates keyword matching rules and base confidences for search routing.
 */
data class SearchRule(
    val type: SearchType,
    val keywords: Set<String>,
    val confidence: Float,
    val description: String = ""
)
