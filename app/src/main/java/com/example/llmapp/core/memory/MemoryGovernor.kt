package com.example.llmapp.core.memory

import com.example.llmapp.core.orchestrator.RetrievedMemory

/**
 * Epistemic state filter. Removes CONTRADICTED memories and
 * tags ASSUMED memories so downstream components know confidence level.
 */
class MemoryGovernor {

    /**
     * Filters retrieved memories by epistemic state.
     * - CONTRADICTED → removed entirely (prevents hallucination recursion)
     * - All others → passed through (confidence scored in MemoryRanker)
     */
    fun filter(memories: List<RetrievedMemory>): List<RetrievedMemory> {
        return memories.filter { it.epistemicState != "CONTRADICTED" }
    }
}
