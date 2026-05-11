package com.example.llmapp.core.governance

import com.example.llmapp.core.database.MemoryEntity

object GovernancePolicyEngine {

    /**
     * Evaluates a list of retrieved semantic memories before they are injected into the LLM prompt.
     * Rule 1: Masks out completely CONTRADICTED memories to prevent hallucination recursion.
     * Rule 2: Injects an uncertainty prefix to ASSUMED memories so the LLM knows it is not an absolute fact.
     */
    fun evaluateRetrievalContext(rawMemories: List<MemoryEntity>): List<String> {
        // Filter out contradicted facts entirely
        val activeMemories = rawMemories.filter { it.epistemicState != EpistemicState.CONTRADICTED.name }
        
        return activeMemories.map { memory ->
            when (memory.epistemicState) {
                EpistemicState.VERIFIED.name -> "[VERIFIED FACT] ${memory.content}"
                EpistemicState.PROBABLE.name -> "[HIGH PROBABILITY] ${memory.content}"
                EpistemicState.ASSUMED.name -> "[ASSUMPTION - DO NOT STATE AS ABSOLUTE FACT] ${memory.content}"
                else -> "[UNKNOWN CONFIDENCE] ${memory.content}"
            }
        }
    }
}
