package com.example.llmapp.core.inference

/**
 * Context Composer — builds final prompts for the Main LLM (Gemma 4 E2B).
 *
 * Responsibilities:
 *   • Fuse memory context + tool outputs into the conversation prompt
 *   • Build a minimal standalone prompt when the main model is unavailable (router fallback)
 *
 * This object does NOT handle routing — that's FunctionGemmaRouter's job.
 */
object ContextComposer {

    /**
     * Fuses parallel execution results (memory, tools) into the conversation prompt.
     *
     * The originalPrompt contains the full multi-turn conversation ending with
     * "<start_of_turn>model\n". This method injects memory and tool results
     * right before that final model turn marker.
     */
    fun buildContextComposerPrompt(
        originalPrompt: String,
        rewrittenQuery: String,
        toolOutputs: Map<String, String>,
        memoryContext: String
    ): String {
        if (toolOutputs.isEmpty() && memoryContext.isBlank()) {
            return originalPrompt
        }

        val injectionTarget = "<start_of_turn>model\n"
        
        val sb = StringBuilder()
        if (memoryContext.isNotBlank()) {
            sb.append("\n[MEMORY RETRIEVED]\n$memoryContext\n")
        }

        if (toolOutputs.isNotEmpty()) {
            sb.append("\n[BACKGROUND TASK RESULTS]\n")
            toolOutputs.forEach { (tool, result) ->
                sb.append("== Task: $tool ==\n$result\n")
            }
            sb.append("\nINSTRUCTION: Please respond to the user's query in a natural, conversational, and human-like manner. Use the above background task results to accurately answer the query.\n")
        }
        
        val injectedContent = sb.toString()
        
        return if (originalPrompt.endsWith(injectionTarget)) {
            val base = originalPrompt.removeSuffix(injectionTarget)
            base + injectedContent + injectionTarget
        } else {
            originalPrompt + injectedContent + injectionTarget
        }
    }

    /**
     * Builds a minimal standalone prompt for the router fallback.
     * Used when the main model fails to load — the router engine
     * answers in degraded mode using this lightweight prompt.
     */
    fun buildRouterFallbackPrompt(
        rawQuery: String,
        toolOutputs: Map<String, String>,
        memoryContext: String
    ): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>system\n")
        sb.append("You are a helpful AI assistant. Answer the user's question directly and concisely.\n")
        
        if (memoryContext.isNotBlank()) {
            sb.append("\n[MEMORY RETRIEVED]\n$memoryContext\n")
        }

        if (toolOutputs.isNotEmpty()) {
            sb.append("\n[BACKGROUND TASK RESULTS]\n")
            toolOutputs.forEach { (tool, result) ->
                sb.append("== Task: $tool ==\n$result\n")
            }
            sb.append("\nINSTRUCTION: Use the above background task results to accurately answer the user's question.\n")
        }
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>user\n")
        sb.append(rawQuery)
        sb.append("\n<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        
        return sb.toString()
    }
}
