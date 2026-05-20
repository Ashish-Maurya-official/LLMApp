package com.example.llmapp.core.memory

/**
 * Handles unified retrieval from episodic and semantic memory systems.
 */
class MemoryRetriever(
    private val episodicMemory: EpisodicMemory,
    private val semanticMemory: SemanticMemory
) {
    fun retrieve(query: String): List<String> {
        // TODO: Retrieve from multiple sources
        return emptyList()
    }
}
