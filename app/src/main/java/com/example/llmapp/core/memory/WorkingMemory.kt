package com.example.llmapp.core.memory

/**
 * Working Memory: the buffer between live conversation and long-term storage.
 *
 * When a user says "Let's call this project Phoenix", it goes into WorkingMemory
 * first — NOT directly into long-term storage. The MemoryConsolidator periodically
 * drains older turns and decides which ones deserve long-term persistence.
 *
 * This prevents every casual statement from becoming a permanent memory.
 */
class WorkingMemory(private val maxTurns: Int = 20) {

    data class WorkingTurn(
        val userText: String,
        val aiText: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val turns = ArrayDeque<WorkingTurn>(maxTurns)

    /**
     * Adds a completed conversation turn to working memory.
     * If the buffer is full, the oldest turn is evicted.
     */
    fun addTurn(userText: String, aiText: String) {
        if (turns.size >= maxTurns) turns.removeFirst()
        turns.addLast(WorkingTurn(userText, aiText))
    }

    /**
     * Returns turns that are ready for consolidation (older than 5 minutes).
     * These are removed from working memory and should be processed by the
     * MemoryConsolidator for potential long-term storage.
     */
    fun drainForConsolidation(): List<WorkingTurn> {
        val cutoff = System.currentTimeMillis() - 5 * 60 * 1000 // 5 minutes
        val ready = turns.filter { it.timestamp < cutoff }
        ready.forEach { turns.remove(it) }
        return ready
    }

    /**
     * Returns all recent turns currently in working memory.
     */
    fun getRecentContext(): List<WorkingTurn> = turns.toList()

    /**
     * Returns the number of turns currently buffered.
     */
    fun size(): Int = turns.size

    /**
     * Clears working memory (e.g., on session switch).
     */
    fun clear() = turns.clear()
}
