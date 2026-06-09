package com.example.llmapp.core.runtime

/**
 * UI-side representation of a cognitive thought.
 * Stores the full update history so the timeline can show the evolution of each thought.
 *
 * Example timeline for one ThoughtItem:
 *   Memory Recall
 *    ├ Searching memory
 *    ├ Found 12 memories
 *    ├ Ranked 4 memories
 *    └ Memory recall complete
 */
data class ThoughtItem(
    val id: String,
    val source: ThoughtSource,
    val title: String,
    val updates: List<String> = emptyList(),
    val state: ThoughtState = ThoughtState.ACTIVE,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Lifecycle state of a thought.
 */
enum class ThoughtState {
    /** Currently happening — UI shows animated dots on the task name */
    ACTIVE,
    /** Done — UI shows ✓ icon */
    COMPLETED
}

/**
 * Typed source of a thought — which cognitive agent produced it.
 * Using an enum instead of strings prevents typo bugs and enables switch exhaustiveness.
 */
enum class ThoughtSource {
    ROUTER,
    MEMORY,
    RAG,
    WEB_SEARCH,
    TOOL_EXECUTOR,
    CONTEXT_COMPOSER,
    RUNTIME
}
