package com.example.llmapp.core.orchestrator

// ── FunctionGemma Router Output ──────────────────────────────────────────
/**
 * Multi-label routing decision from FunctionGemma 270M.
 * The router NEVER answers users — it only classifies and routes.
 *
 * Multiple flags can be true simultaneously for compound queries:
 *   "What did I work on at YMGrad and search for their latest updates?"
 *   → needMemory=true, needTools=true, toolName="WEB_SEARCH"
 *
 * Dual confidence:
 *   confidence         — how sure the router is about the route classification
 *   reasoningConfidence — how sure it is about the reasoning (helps debug ambiguous queries)
 */
data class RoutingDecision(
    val intent: String,                          // "memory_recall", "reminder", "education", "chat", etc.
    val needMemory: Boolean = false,             // Should MemoryAgent recall context?
    val needRag: Boolean = false,                // Should RAG search documents?
    val needTools: Boolean = false,              // Should a tool be executed?
    val toolName: String? = null,                // Which tool (if needTools=true)
    val toolQuery: String? = null,               // Query/args for the tool
    val needMemoryExtraction: Boolean = false,   // Extract new facts post-generation (via Gemma 4 E2B)
    val confidence: Float = 0.0f,                // Route classification confidence (< 0.7 → fallback)
    val reasoningConfidence: Float = 0.0f         // How sure about the reasoning behind the route
) {
    companion object {
        /** Fallback when router times out, fails, or confidence is too low */
        val CHAT_FALLBACK = RoutingDecision(
            intent = "",
            confidence = 0.0f,
            reasoningConfidence = 0.0f
        )
    }
}

/**
 * Global cancellation token used to halt operations across all subsystems
 * (e.g., STT, Orchestrator, Tools, Main LLM, TTS) during a barge-in or stop event.
 */
class CancellationToken {
    @Volatile
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }

    fun reset() {
        isCancelled = false
    }
}

// ── Tool Request (used by ToolRegistry + CognitiveWorker) ────────────────
data class ToolRequest(
    val name: String,
    val priority: Int,
    val required: Boolean,
    val query: String? = null,
    val parameters: Map<String, String>? = null
)

// ── Memory Type with recall cost for budgeting ───────────────────────────
enum class MemoryType(val recallCost: Int) {
    PROFILE(1),     // Cheap: key-value lookups
    SEMANTIC(3),    // Medium: FTS search
    EPISODIC(5);    // Expensive: episode scanning

    companion object {
        fun fromString(s: String): MemoryType? = entries.find { it.name.equals(s, ignoreCase = true) }
    }
}

// ── MemoryAgent internal types ───────────────────────────────────────────
/**
 * What the MemoryAgent uses internally. Built from MemoryPlan + originalQuery.
 */
data class MemoryGoal(
    val objective: String,
    val categories: Set<MemoryType>,
    val originalQuery: String,      // Raw user query — MemoryPlanner extracts terms
    val maxResults: Int,            // Set by retrieval budgeting (importance-based)
    val costBudget: Int             // Max total recall cost across stores
)

/**
 * Intermediate retrieval result before ranking. Each subsystem can produce these.
 */
data class RetrievedMemory(
    val id: Long,
    val content: String,
    val sourceType: MemoryType,
    val trustZone: Int = 2,         // 0=Identity, 1=User Explicit, 2=Inferred, 3=External
    val importanceScore: Float = 0.5f,
    val epistemicState: String = "ASSUMED",
    val accessCount: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val lineageId: String? = null
)

/**
 * A memory after multi-factor ranking with per-memory scoring + source attribution.
 */
data class RankedMemory(
    val id: Long,
    val fact: String,
    val confidence: Float,          // Per-memory confidence from epistemic state
    val trust: Float,               // TrustZone weight (0-1)
    val importance: Float,          // Creation-time importance score
    val score: Float,               // Final composite score
    val timestamp: Long,
    // Source attribution — for "why do you think that?" explainability
    val sourceType: MemoryType,
    val sourceId: Long,
    val lineageId: String?          // Session that created this memory
)

/**
 * Final output from MemoryAgent after all pipeline stages.
 */
data class MemoryResult(
    val summary: String,            // LLM-summarized (100 token budget)
    val rankedFacts: List<RankedMemory>,
    val conflicts: List<String>,    // "Evolution: X (2024) → Y (2026)"
    val confidence: Float = 0.0f,   // Retrieval confidence: < 0.5 → ignore memories
    val isEmpty: Boolean = false
) {
    companion object {
        val EMPTY = MemoryResult("", emptyList(), emptyList(), confidence = 0.0f, isEmpty = true)
    }

    fun toContextString(): String {
        if (isEmpty) return ""
        return summary.ifBlank {
            rankedFacts.joinToString("\n") { "- ${it.fact}" }
        }
    }
}


/**
 * State tracking for the continuous cognition runtime
 */
data class RuntimeState(
    val currentTask: String? = null,
    val activeStreams: List<String> = emptyList(),
    val interruptionState: Boolean = false,
    val cognitiveLoad: Float = 0.0f
)
