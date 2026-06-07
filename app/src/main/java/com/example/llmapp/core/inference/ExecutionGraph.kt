package com.example.llmapp.core.inference

import org.json.JSONObject
import android.util.Log

object ExecutionGraph {

    /**
     * Level 1: Orchestrator Prompt
     * Forces the small 0.5B model to output a strict JSON plan.
     */
    fun buildOrchestratorPrompt(query: String): String {
        return """
            <start_of_turn>system
            You are the Cognitive Orchestrator of an Android OS.
            Analyze the user query and output ONLY valid JSON using the following schema.
            Do not include any explanation or markdown formatting outside the JSON block.
            
            RULES:
            1. If the user says a simple greeting like "hi", "hello", "how are you", cognitiveDepth MUST be 1, tools MUST be NONE, and memoryPlan.enabled MUST be false.
            2. ONLY use WEB_SEARCH if the user asks for real-time information, news, or facts you don't know. If using WEB_SEARCH, provide a specific search string in the 'query' field.
            3. ONLY use FLASHLIGHT if the user explicitly asks to turn on or off the flashlight.
            4. If the user asks for coding, complex reasoning, or web search, cognitiveDepth MUST be 2. Otherwise, set it to 1.
            5. Set memoryPlan.enabled=true when the user references personal info, past conversations, preferences, or previously discussed facts. Categories: PROFILE (name, DOB, location, preferences), SEMANTIC (learned facts), EPISODIC (past conversation topics). Set goal to describe WHAT to find. Set importance 0-1 (1.0=critical to answer, 0.3=nice to have). Do NOT generate search keywords.
            6. memoryPlan and tools are independent. Both can be active simultaneously.

            SCHEMA:
            {
              "intent": "chat",
              "confidence": 1.0,
              "cognitiveDepth": 1,
              "memoryPlan": {
                "enabled": false,
                "goal": "",
                "categories": [],
                "importance": 0.0
              },
              "tools": [
                {
                  "name": "NONE",
                  "query": null
                }
              ],
              "rewrittenQuery": "string"
            }
            <end_of_turn>
            <start_of_turn>user
            $query
            <end_of_turn>
            <start_of_turn>model
            {
        """.trimIndent()
    }

    /**
     * Level 2: Context Composer
     * Fuses the results from parallel execution into a final context window for the Main LLM.
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

        // We need to inject the background task results right before the final model turn.
        // originalPrompt always ends with "<start_of_turn>model\n"
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
     * Level 1.5: Minimal Orchestrator Fallback Composer
     * The 0.5B model cannot handle massive system prompts and conversation history.
     * This builds a strictly minimal prompt focused ONLY on the current query and tool results.
     */
    fun buildOrchestratorFallbackPrompt(
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

    /**
     * Safely parses the JSON output of the Orchestrator, falling back to a default Level 2 plan
     * if the small LLM hallucinates or breaks JSON structure.
     */
    fun parseCognitivePlan(rawJson: String, originalQuery: String): com.example.llmapp.core.orchestrator.CognitivePlan {
        try {
            // The prompt ends with `{` so we must prepend it to the raw output
            val cleanJson = if (rawJson.trim().startsWith("{")) rawJson else "{\n$rawJson"
            // Strip markdown backticks if present
            val finalJson = cleanJson.replace(Regex("```json|```"), "").trim()
            
            val obj = JSONObject(finalJson)
            
            val toolsArray = obj.optJSONArray("tools")
            val toolRequests = mutableListOf<com.example.llmapp.core.orchestrator.ToolRequest>()
            if (toolsArray != null) {
                for (i in 0 until toolsArray.length()) {
                    val t = toolsArray.getJSONObject(i)
                    val paramsObj = t.optJSONObject("parameters")
                    val paramsMap = mutableMapOf<String, String>()
                    if (paramsObj != null) {
                        val keys = paramsObj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            paramsMap[key] = paramsObj.getString(key)
                        }
                    }
                    toolRequests.add(
                        com.example.llmapp.core.orchestrator.ToolRequest(
                            name = t.optString("name", "NONE"),
                            priority = t.optInt("priority", 1),
                            required = t.optBoolean("required", false),
                            query = if (t.isNull("query")) null else t.optString("query"),
                            parameters = if (paramsMap.isEmpty()) null else paramsMap
                        )
                    )
                }
            }
            
            // Parse memoryPlan (top-level, separate from tools)
            val memPlanObj = obj.optJSONObject("memoryPlan")
            val memCategories = mutableListOf<String>()
            memPlanObj?.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) memCategories.add(arr.getString(i))
            }
            val memoryPlan = com.example.llmapp.core.orchestrator.MemoryPlan(
                enabled = memPlanObj?.optBoolean("enabled", false) ?: false,
                goal = memPlanObj?.optString("goal", "") ?: "",
                categories = memCategories,
                importance = memPlanObj?.optDouble("importance", 0.0)?.toFloat() ?: 0f
            )
            
            return com.example.llmapp.core.orchestrator.CognitivePlan(
                intent = obj.optString("intent", "chat"),
                confidence = obj.optDouble("confidence", 1.0).toFloat(),
                cognitiveDepth = obj.optInt("cognitiveDepth", 2),
                tools = toolRequests,
                memoryPlan = memoryPlan,
                rewrittenQuery = obj.optString("rewrittenQuery", originalQuery)
            )

        } catch (e: Exception) {
            Log.e("ExecutionGraph", "Failed to parse orchestrator JSON. Falling back to default L2 plan. Error: ${e.message}")
            return com.example.llmapp.core.orchestrator.CognitivePlan(
                intent = "fallback",
                confidence = 0.5f,
                cognitiveDepth = 2,
                tools = listOf(com.example.llmapp.core.orchestrator.ToolRequest("NONE", 1, false)),
                memoryPlan = com.example.llmapp.core.orchestrator.MemoryPlan.DISABLED,
                rewrittenQuery = originalQuery
            )
        }
    }
}
