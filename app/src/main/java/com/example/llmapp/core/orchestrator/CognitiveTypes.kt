package com.example.llmapp.core.orchestrator

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

/**
 * Represents the structured JSON output from the Level 1 Cognitive Orchestrator.
 */
data class CognitivePlan(
    val intent: String,
    val confidence: Float,
    val cognitiveDepth: Int,
    val tools: List<ToolRequest>,
    val memoryPlan: MemoryPlan,
    val memoryExtraction: MemoryExtractionPlan,
    val rewrittenQuery: String
)

data class ToolRequest(
    val name: String,
    val priority: Int,
    val required: Boolean,
    val query: String? = null,
    val parameters: Map<String, String>? = null
)

/**
 * Directs the background MemoryExtractor on whether to save new facts.
 */
data class MemoryExtractionPlan(
    val enabled: Boolean,
    val confidence: Float,
    val reason: String
) {
    companion object {
        val DISABLED = MemoryExtractionPlan(enabled = false, confidence = 0f, reason = "none")
    }
}

// ── Orchestrator defines WHAT, not HOW ───────────────────────────────────
/**
 * The orchestrator's memory directive. It declares the objective and which
 * memory categories are relevant. The MemoryAgent decides how to retrieve.
 */
data class MemoryPlan(
    val enabled: Boolean,
    val goal: String,               // "Find user's pet information"
    val categories: List<String>,   // ["PROFILE", "SEMANTIC", "EPISODIC"]
    val importance: Float           // 0-1 → controls retrieval budget + cost limit
) {
    companion object {
        val DISABLED = MemoryPlan(enabled = false, goal = "", categories = emptyList(), importance = 0f)
    }
}

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
    val isEmpty: Boolean = false
) {
    companion object {
        val EMPTY = MemoryResult("", emptyList(), emptyList(), true)
    }

    fun toContextString(): String {
        if (isEmpty) return ""
        return summary.ifBlank {
            rankedFacts.joinToString("\n") { "- ${it.fact}" }
        }
    }
}

/**
 * Execution Graph Nodes mapping
 */
data class ExecutionNode(
    val id: String,
    val toolRequest: ToolRequest
)

data class Edge(
    val fromNodeId: String,
    val toNodeId: String
)

data class ExecutionGraph(
    val nodes: List<ExecutionNode>,
    val dependencies: List<Edge>
)

/**
 * State tracking for the continuous cognition runtime
 */
data class RuntimeState(
    val currentTask: String? = null,
    val activeStreams: List<String> = emptyList(),
    val interruptionState: Boolean = false,
    val cognitiveLoad: Float = 0.0f
)
