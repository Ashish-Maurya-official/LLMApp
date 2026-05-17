package com.example.llmapp.core.search.policies

/**
 * Mid-size, fast-snippet news retrieval policy.
 */
object NewsPolicy : SearchPolicy {
    override val maxResults: Int = 3
    override val allowDeepBrowse: Boolean = false
    override val freshnessRequired: Boolean = true
}
