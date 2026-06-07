package com.example.llmapp.core.memory

/**
 * Generates search terms from the MemoryGoal objective + original user query.
 * The orchestrator defines WHAT to find; the Planner decides HOW to search.
 */
class MemoryPlanner {

    companion object {
        private val STOP_WORDS = setOf(
            "the", "what", "where", "when", "how", "who", "which",
            "did", "does", "was", "were", "are", "been", "have",
            "has", "had", "this", "that", "about", "with", "from",
            "tell", "told", "said", "know", "remember", "recall",
            "can", "you", "your", "please", "just", "will", "would",
            "could", "should", "shall", "may", "might", "let",
            "for", "and", "but", "not", "its", "our", "some"
        )
    }

    /**
     * Extracts meaningful search keywords from the goal objective and original query.
     * Filters stop words, short words, and deduplicates.
     */
    fun generateSearchTerms(goal: com.example.llmapp.core.orchestrator.MemoryGoal): List<String> {
        val combined = "${goal.objective} ${goal.originalQuery}"
        return combined
            .replace(Regex("[^a-zA-Z0-9 ]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 }
            .map { it.lowercase() }
            .filter { it !in STOP_WORDS }
            .distinct()
    }
}
