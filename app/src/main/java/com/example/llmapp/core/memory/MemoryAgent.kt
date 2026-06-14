package com.example.llmapp.core.memory

import android.util.Log
import com.example.llmapp.core.database.MemoryDao
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.orchestrator.MemoryGoal
import com.example.llmapp.core.orchestrator.MemoryResult

/**
 * Orchestrates the multi-stage memory recall pipeline (Cache -> Plan -> Retrieve -> Govern -> Rank -> Resolve -> Summarize).
 */
class MemoryAgent(
    private val memoryDao: MemoryDao,
    var llmInferenceManager: LlmInferenceManager? = null,
    var thoughtEmitter: (suspend (content: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MemoryAgent"
    }

    // Subsystems
    private val cache = MemoryRecallCache()
    private val planner = MemoryPlanner()
    private val retriever = MemoryRetriever(memoryDao)
    private val governor = MemoryGovernor()
    private val ranker = MemoryRanker()
    private val conflictResolver = MemoryConflictResolver()
    private val summarizer = MemorySummarizer()

    /**
     * Executes the memory recall pipeline.
     */
    suspend fun recall(goal: MemoryGoal): MemoryResult {
        Log.d(TAG, "Recall: objective='${goal.objective}', categories=${goal.categories}, budget=${goal.maxResults}, costBudget=${goal.costBudget}")

        // Cache check
        cache.get(goal)?.let { cached ->
            Log.d(TAG, "Cache HIT for '${goal.objective}'")
            thoughtEmitter?.invoke("Cache hit — using previous recall")
            return cached
        }

        // Planner stage
        thoughtEmitter?.invoke("Generating search terms")
        val searchTerms = planner.generateSearchTerms(goal)
        Log.d(TAG, "Stage 1 (Plan): search terms = $searchTerms")

        if (searchTerms.isEmpty()) {
            Log.d(TAG, "No meaningful search terms. Returning EMPTY.")
            thoughtEmitter?.invoke("No search terms found")
            return MemoryResult.EMPTY
        }

        // Retrieval stage
        thoughtEmitter?.invoke("Searching ${goal.categories.size} memory stores")
        val raw = retriever.retrieve(searchTerms, goal.categories, goal.maxResults, goal.costBudget)
        if (raw.isEmpty()) {
            Log.d(TAG, "Stage 2 (Retrieve): No results found. Returning EMPTY.")
            thoughtEmitter?.invoke("No results found")
            return MemoryResult.EMPTY
        }
        Log.d(TAG, "Stage 2 (Retrieve): ${raw.size} raw results")
        thoughtEmitter?.invoke("Found ${raw.size} memories")

        // Governance stage
        val governed = governor.filter(raw)
        if (governed.isEmpty()) {
            Log.d(TAG, "Stage 3 (Govern): All memories filtered out. Returning EMPTY.")
            thoughtEmitter?.invoke("All memories filtered by governance")
            return MemoryResult.EMPTY
        }
        Log.d(TAG, "Stage 3 (Govern): ${governed.size} after governance (${raw.size - governed.size} removed)")

        // Ranking stage
        thoughtEmitter?.invoke("Ranking ${governed.size} results")
        val ranked = ranker.rank(governed)
        Log.d(TAG, "Stage 4 (Rank): top=${ranked.firstOrNull()?.fact?.take(50)} (score=${ranked.firstOrNull()?.score})")

        // Conflict resolution
        val (resolved, conflicts) = conflictResolver.resolve(ranked)
        if (conflicts.isNotEmpty()) {
            Log.d(TAG, "Stage 5 (Conflicts): ${conflicts.size} detected: $conflicts")
            thoughtEmitter?.invoke("Resolved ${conflicts.size} conflicts")
        }

        // Summarization stage
        thoughtEmitter?.invoke("Summarizing ${resolved.size} facts")
        val summary = summarizer.summarize(goal.objective, resolved, conflicts, llmInferenceManager)
        Log.d(TAG, "Stage 6 (Summarize): ${summary.length} chars")

        // Update access metrics
        retriever.trackAccess(resolved)

        val result = MemoryResult(
            summary = summary,
            rankedFacts = resolved,
            conflicts = conflicts
        )

        // Cache the result
        cache.put(goal, result)

        return result
    }

    /**
     * Invalidates the recall cache.
     */
    fun invalidateCache() {
        cache.invalidate()
    }
}
