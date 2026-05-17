package com.example.llmapp.core.search.orchestration

import com.example.llmapp.core.search.models.SearchResponse
import java.security.MessageDigest

/**
 * In-memory LRU cache for search results with TTL-based expiry.
 *
 * - No DB writes — zero latency
 * - Max 50 entries (LRU eviction)
 * - TTL auto-detected from query type:
 *   - weather: 5 min
 *   - news/breaking: 15 min
 *   - generic: 24 hr
 */
class SearchCache(private val maxEntries: Int = 50) {

    private data class CacheEntry(
        val response: SearchResponse,
        val expiresAt: Long
    )

    // LinkedHashMap as LRU (accessOrder = true)
    private val store: LinkedHashMap<String, CacheEntry> = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > maxEntries
        }
    }

    private companion object {
        const val TTL_WEATHER_MS = 5 * 60 * 1000L       // 5 minutes
        const val TTL_NEWS_MS = 15 * 60 * 1000L          // 15 minutes
        const val TTL_GENERIC_MS = 24 * 60 * 60 * 1000L  // 24 hours

        val WEATHER_KEYWORDS = setOf("weather", "temperature", "forecast", "rain", "humidity")
        val NEWS_KEYWORDS = setOf("news", "breaking", "latest", "headline", "score", "live", "match")
    }

    fun get(query: String): SearchResponse? {
        val key = hash(query)
        val entry = store[key] ?: return null
        return if (System.currentTimeMillis() < entry.expiresAt) {
            entry.response.copy(fromCache = true)
        } else {
            store.remove(key)
            null
        }
    }

    fun put(query: String, response: SearchResponse) {
        val key = hash(query)
        val ttl = detectTtl(query)
        store[key] = CacheEntry(
            response = response,
            expiresAt = System.currentTimeMillis() + ttl
        )
    }

    fun invalidate(query: String) { store.remove(hash(query)) }

    fun clear() = store.clear()

    private fun hash(query: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(query.lowercase().trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun detectTtl(query: String): Long {
        val lower = query.lowercase()
        return when {
            WEATHER_KEYWORDS.any { lower.contains(it) } -> TTL_WEATHER_MS
            NEWS_KEYWORDS.any { lower.contains(it) } -> TTL_NEWS_MS
            else -> TTL_GENERIC_MS
        }
    }
}
