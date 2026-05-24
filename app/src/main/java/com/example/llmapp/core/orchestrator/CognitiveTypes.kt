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
    val memoryRequirements: MemoryRequest,
    val rewrittenQuery: String
)

data class ToolRequest(
    val name: String,
    val priority: Int,
    val required: Boolean,
    val parameters: Map<String, String>? = null
)

data class MemoryRequest(
    val retrieve: Boolean,
    val categories: List<String>
)

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
