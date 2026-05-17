package com.example.llmapp.core.search.policies

/**
 * Interface detailing execution parameters for specific search intents.
 */
interface SearchPolicy {
    val maxResults: Int
    val allowDeepBrowse: Boolean
    val freshnessRequired: Boolean
}
