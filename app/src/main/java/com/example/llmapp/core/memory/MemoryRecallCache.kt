package com.example.llmapp.core.memory

import com.example.llmapp.core.orchestrator.MemoryGoal
import com.example.llmapp.core.orchestrator.MemoryResult

/**
 * LRU cache for memory recall results with TTL-based expiration.
 * Prevents repeated DB+LLM calls for identical or similar queries.
 * e.g., "What's my name?" → "What's my name again?" → cache hit.
 */
class MemoryRecallCache(
    private val maxSize: Int = 32,
    private val ttlMs: Long = 5 * 60 * 1000 // 5 minutes
) {
    private data class CachedRecall(
        val result: MemoryResult,
        val timestamp: Long
    )

    // Access-ordered LinkedHashMap for LRU eviction
    private val cache = object : LinkedHashMap<String, CachedRecall>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedRecall>?): Boolean {
            return size > maxSize
        }
    }

    /**
     * Returns cached result if available and not expired.
     */
    fun get(goal: MemoryGoal): MemoryResult? {
        val key = cacheKey(goal)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(key)
            return null
        }
        return entry.result
    }

    /**
     * Stores a recall result in cache.
     */
    fun put(goal: MemoryGoal, result: MemoryResult) {
        if (result.isEmpty) return // Don't cache empty results
        cache[cacheKey(goal)] = CachedRecall(result, System.currentTimeMillis())
    }

    /**
     * Invalidates the entire cache (e.g., after new memories are written).
     */
    fun invalidate() {
        cache.clear()
    }

    /**
     * Cache key: hash of objective + categories.
     * Similar queries with the same intent will share cache entries.
     */
    private fun cacheKey(goal: MemoryGoal): String {
        return "${goal.objective.lowercase().hashCode()}_${goal.categories.hashCode()}"
    }
}
