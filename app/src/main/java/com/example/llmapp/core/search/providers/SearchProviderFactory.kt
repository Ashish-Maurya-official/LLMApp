package com.example.llmapp.core.search.providers

import com.example.llmapp.core.search.settings.SearchPreferences
import com.example.llmapp.core.search.settings.SecureSearchStorage

/**
 * Creates the active [SearchProvider] based on user preferences.
 * Adding a new provider in the future only requires a new branch here.
 */
object SearchProviderFactory {

    const val PROVIDER_GOOGLE = "Google Custom Search"
    const val PROVIDER_DUCKDUCKGO = "DuckDuckGo"
    const val PROVIDER_AUTO = "Auto (Fallback)"

    fun create(
        preferences: SearchPreferences,
        secureStorage: SecureSearchStorage
    ): SearchProvider {
        return when (preferences.activeProvider) {
            PROVIDER_GOOGLE -> GoogleSearchProvider(secureStorage)
            PROVIDER_DUCKDUCKGO -> DuckDuckGoProvider()
            else -> {
                // AUTO: prefer Google if configured, else DuckDuckGo
                val google = GoogleSearchProvider(secureStorage)
                if (google.isConfigured()) google else DuckDuckGoProvider()
            }
        }
    }

    fun createFallback(): SearchProvider = DuckDuckGoProvider()

    val availableProviders = listOf(PROVIDER_AUTO, PROVIDER_GOOGLE, PROVIDER_DUCKDUCKGO)
}
