package com.example.llmapp.core.memory

/**
 * Moves important information from WorkingMemory to EpisodicMemory
 * when a conversation session ends.
 */
class MemoryConsolidator(
    private val episodicMemory: EpisodicMemory,
    private val semanticMemory: SemanticMemory
) {
    fun consolidate(workingMemory: WorkingMemory) {
        // TODO: Summarize and store
    }
}
