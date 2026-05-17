package com.example.llmapp.core.search.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.llmapp.core.search.providers.SearchProviderFactory

/**
 * Non-secret search preferences stored in regular SharedPreferences.
 * Secrets (API keys) are in [SecureSearchStorage].
 */
class SearchPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("search_preferences", Context.MODE_PRIVATE)

    var webSearchEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var activeProvider: String
        get() = prefs.getString(KEY_PROVIDER, SearchProviderFactory.PROVIDER_AUTO) ?: SearchProviderFactory.PROVIDER_AUTO
        set(value) = prefs.edit().putString(KEY_PROVIDER, value).apply()

    var autoSearch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SEARCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SEARCH, value).apply()

    var maxResults: Int
        get() = prefs.getInt(KEY_MAX_RESULTS, 3)
        set(value) = prefs.edit().putInt(KEY_MAX_RESULTS, value.coerceIn(1, 5)).apply()

    var cacheDuration: String
        get() = prefs.getString(KEY_CACHE_DURATION, CACHE_STANDARD) ?: CACHE_STANDARD
        set(value) = prefs.edit().putString(KEY_CACHE_DURATION, value).apply()

    var webRetrievalMode: WebRetrievalMode
        get() = WebRetrievalMode.valueOf(prefs.getString(KEY_RETRIEVAL_MODE, WebRetrievalMode.FAST.name) ?: WebRetrievalMode.FAST.name)
        set(value) = prefs.edit().putString(KEY_RETRIEVAL_MODE, value.name).apply()

    private companion object {
        const val KEY_ENABLED = "web_search_enabled"
        const val KEY_PROVIDER = "active_provider"
        const val KEY_AUTO_SEARCH = "auto_search"
        const val KEY_MAX_RESULTS = "max_results"
        const val KEY_CACHE_DURATION = "cache_duration"
        const val KEY_RETRIEVAL_MODE = "web_retrieval_mode"

        const val CACHE_DISABLED = "Disabled"
        const val CACHE_SHORT = "Short (5 min)"
        const val CACHE_STANDARD = "Standard (15 min)"
        const val CACHE_LONG = "Long (24 hr)"
    }
}

enum class WebRetrievalMode {
    FAST,       // Snippets only
    BALANCED,   // Snippets + selective deep fetching based on intent
    DEEP        // Always attempt to fetch and extract the top URLs
}
