package com.example.llmapp.core.search.policies

/**
 * High-freshness policy tailored specifically for Weather data retrieval.
 */
object WeatherPolicy : SearchPolicy {
    override val maxResults: Int = 2
    override val allowDeepBrowse: Boolean = true
    override val freshnessRequired: Boolean = true
}
