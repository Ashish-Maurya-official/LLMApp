package com.example.llmapp.core.memory

import android.util.Log
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.orchestrator.RankedMemory

/**
 * LLM-powered memory summarization. Condenses raw facts into 2-3 sentences
 * that directly address the recall objective, saving tokens enormously.
 * Uses the Orchestrator (0.5B) model — lightweight, fast.
 */
class MemorySummarizer {

    companion object {
        private const val TAG = "MemorySummarizer"
        const val MAX_SUMMARY_TOKENS = 100
        private const val MAX_SUMMARY_CHARS = MAX_SUMMARY_TOKENS * 4 // ~4 chars/token
    }

    /**
     * Summarizes ranked memories into a concise context string.
     * Falls back to empty string (MemoryResult.toContextString() uses raw facts).
     */
    suspend fun summarize(
        objective: String,
        memories: List<RankedMemory>,
        conflicts: List<String>,
        llm: LlmInferenceManager?
    ): String {
        if (llm == null || memories.isEmpty()) return ""

        val factsBlock = memories.take(8).joinToString("\n") { mem ->
            val confidenceTag = if (mem.confidence > 0.7f) "HIGH" else "MED"
            "- [$confidenceTag] ${mem.fact}"
        }

        val conflictsBlock = if (conflicts.isNotEmpty()) {
            "\n\nEvolutions/Conflicts:\n${conflicts.joinToString("\n") { "- $it" }}"
        } else ""

        val prompt = """
            <start_of_turn>system
            You are a Memory Summarizer. Given retrieved memory facts and a recall objective,
            produce a concise summary (2-3 sentences max, under $MAX_SUMMARY_TOKENS tokens).
            State confidence level. Mention any evolutions or conflicts.
            Do NOT add information that is not in the facts.
            Do NOT use markdown formatting.
            <end_of_turn>
            <start_of_turn>user
            Recall objective: $objective

            Retrieved facts:
            $factsBlock$conflictsBlock
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()

        return try {
            val result = llm.generateOrchestratorResponse(prompt).trim()
            val capped = result.take(MAX_SUMMARY_CHARS)
            Log.d(TAG, "Summarized ${memories.size} facts → ${capped.length} chars")
            capped
        } catch (e: Exception) {
            Log.w(TAG, "Summarization failed, will use raw facts: ${e.message}")
            "" // Fallback: toContextString() uses raw facts
        }
    }
}
