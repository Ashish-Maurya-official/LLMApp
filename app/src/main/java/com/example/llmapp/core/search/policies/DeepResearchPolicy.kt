package com.example.llmapp.core.search.policies

/**
 * Deep, comprehensive multi-result policy for deep researching.
 */
object DeepResearchPolicy : SearchPolicy {
    override val maxResults: Int = 5
    override val allowDeepBrowse: Boolean = true
    override val freshnessRequired: Boolean = true
}
