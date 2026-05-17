package com.example.llmapp.core.search.policies

/**
 * Standard baseline search policy.
 */
object DefaultPolicy : SearchPolicy {
    override val maxResults: Int = 3
    override val allowDeepBrowse: Boolean = false
    override val freshnessRequired: Boolean = false
}
