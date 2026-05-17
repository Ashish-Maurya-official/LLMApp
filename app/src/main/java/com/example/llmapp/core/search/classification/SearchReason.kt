package com.example.llmapp.core.search.classification

/**
 * Semantic reason outlining why the dynamic retrieval engine executed a live web query.
 */
enum class SearchReason {
    REALTIME_INFORMATION,
    WEBSITE_READING,
    LIVE_SCORES,
    MARKET_DATA,
    WEATHER_DATA,
    LOCATION_DATA,
    NEWS_EVENT,
    TECHNICAL_DOCS_LIVE,
    USER_FORCED,
    CONTEXTUAL_INHERITANCE,
    NONE
}
