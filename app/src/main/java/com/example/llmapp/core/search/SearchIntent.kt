package com.example.llmapp.core.search

import com.example.llmapp.core.search.classification.SearchReason

/**
 * Structured model representing search intent metadata for system-driven orchestration.
 */
data class SearchIntent(
    val needed: Boolean,
    val type: SearchType,
    val query: String?,
    val confidence: Float,
    val reason: SearchReason = SearchReason.NONE
)
