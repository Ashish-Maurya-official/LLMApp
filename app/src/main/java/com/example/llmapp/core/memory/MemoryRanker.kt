package com.example.llmapp.core.memory

import com.example.llmapp.core.orchestrator.RankedMemory
import com.example.llmapp.core.orchestrator.RetrievedMemory

/**
 * Multi-factor ranking with per-memory scoring.
 * Composite = trust(0.25) + recency(0.25) + usage(0.15) + importance(0.35)
 */
class MemoryRanker {

    /**
     * Ranks retrieved memories by composite score and returns RankedMemory objects
     * with per-memory confidence, trust, importance, and attribution.
     */
    fun rank(memories: List<RetrievedMemory>): List<RankedMemory> {
        val now = System.currentTimeMillis()

        return memories.map { mem ->
            val trustWeight = when (mem.trustZone) {
                0 -> 1.0f    // Identity (system-level)
                1 -> 0.9f    // User Explicit
                2 -> 0.6f    // Agent Inferred
                else -> 0.3f // External
            }

            // Recency: exponential decay over days
            val ageMs = (now - mem.timestamp).coerceAtLeast(0)
            val ageDays = ageMs / (24 * 3600_000f)
            val recencyScore = (1.0f / (1 + ageDays)).coerceIn(0f, 1f)

            // Usage frequency: capped at 10 accesses = 1.0
            val usageScore = (mem.accessCount / 10f).coerceIn(0f, 1f)

            // Importance: creation-time importance score
            val importanceScore = mem.importanceScore

            // Composite score
            val composite = trustWeight * 0.25f +
                             recencyScore * 0.25f +
                             usageScore * 0.15f +
                             importanceScore * 0.35f

            // Confidence from epistemic state
            val confidence = when (mem.epistemicState) {
                "VERIFIED" -> 0.95f
                "PROBABLE" -> 0.75f
                "ASSUMED" -> 0.5f
                else -> 0.3f
            }

            RankedMemory(
                id = mem.id,
                fact = mem.content,
                confidence = confidence,
                trust = trustWeight,
                importance = importanceScore,
                score = composite,
                timestamp = mem.timestamp,
                sourceType = mem.sourceType,
                sourceId = mem.id,
                lineageId = mem.lineageId
            )
        }.sortedByDescending { it.score }
    }
}
