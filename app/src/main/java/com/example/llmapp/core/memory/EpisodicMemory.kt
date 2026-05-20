package com.example.llmapp.core.memory

/**
 * Long-term memory for past conversations, user preferences, and events.
 */
class EpisodicMemory {
    fun saveEpisode(episode: String) {
        // TODO: Persist to database
    }

    fun retrieveRelevant(query: String): List<String> {
        // TODO: Vector search
        return emptyList()
    }
}
