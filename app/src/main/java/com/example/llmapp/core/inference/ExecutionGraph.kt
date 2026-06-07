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
            7. memoryExtraction: Evaluate if the user states NEW facts. 
               DO NOT extract: Temporary emotions ("I am tired"), Temporary physical states, Short-term intentions ("I might learn Rust"), One-off events, Speculation, Questions ("Should I buy a MacBook?").
               ONLY extract: Identity ("My name is Ashish"), Occupation ("I work at YMGrad"), Long-term preferences ("I primarily use Flutter"), Skills, Projects, Persistent habits, Explicit remember/save requests ("Remember that I use Vim").

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
              "memoryExtraction": {
                "enabled": false,
                "confidence": 0.0,
                "reason": "short explanation"
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

            // Parse memoryExtraction — HYBRID GATE
            // The 0.5B model often echoes schema defaults (enabled=false, reason="short explanation")
            // without actually reasoning. We can trust an explicit YES, but must verify a NO.
            val extractionObj = obj.optJSONObject("memoryExtraction")
            val memoryExtraction: com.example.llmapp.core.orchestrator.MemoryExtractionPlan
            if (extractionObj != null) {
                val modelEnabled = extractionObj.optBoolean("enabled", false)
                val modelConfidence = extractionObj.optDouble("confidence", 0.0).toFloat()
                val modelReason = extractionObj.optString("reason", "none")
                
                if (modelEnabled && modelConfidence > 0.0f) {
                    // Orchestrator explicitly says YES with real confidence → trust it
                    Log.d("ExecutionGraph", "Orchestrator says EXTRACT: conf=$modelConfidence, reason=$modelReason")
                    memoryExtraction = com.example.llmapp.core.orchestrator.MemoryExtractionPlan(
                        enabled = true,
                        confidence = modelConfidence,
                        reason = modelReason
                    )
                } else {
                    // Orchestrator says NO — but 0.5B models are unreliable rejectors.
                    // Cross-check with heuristic to catch false negatives.
                    val heuristic = inferMemoryExtractionFromQuery(originalQuery)
                    if (heuristic.enabled) {
                        Log.d("ExecutionGraph", "Orchestrator said NO (reason=$modelReason) but heuristic detected personal facts. Overriding.")
                        memoryExtraction = heuristic
                    } else {
                        Log.d("ExecutionGraph", "Both orchestrator and heuristic agree: no extraction needed.")
                        memoryExtraction = com.example.llmapp.core.orchestrator.MemoryExtractionPlan.DISABLED
                    }
                }
            } else {
                // Field missing entirely → heuristic only
                Log.d("ExecutionGraph", "Orchestrator omitted memoryExtraction. Applying heuristic fallback.")
                memoryExtraction = inferMemoryExtractionFromQuery(originalQuery)
            }
            
            return com.example.llmapp.core.orchestrator.CognitivePlan(
                intent = obj.optString("intent", "chat"),
                confidence = obj.optDouble("confidence", 1.0).toFloat(),
                cognitiveDepth = obj.optInt("cognitiveDepth", 1),
                tools = toolRequests,
                memoryPlan = memoryPlan,
                memoryExtraction = memoryExtraction,
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
                memoryExtraction = com.example.llmapp.core.orchestrator.MemoryExtractionPlan.DISABLED,
                rewrittenQuery = originalQuery
            )
        }
    }

    /**
     * Lightweight heuristic fallback for when the 0.5B orchestrator omits the
     * memoryExtraction field entirely. This is NOT a replacement for the LLM
     * decision — it's a safety net.
     *
     * Design: Detect strong personal-fact signals in the query while explicitly
     * rejecting transient states. Returns a plan with 0.8 confidence so it
     * clears the 0.7 threshold in the scheduler.
     */
    private fun inferMemoryExtractionFromQuery(query: String): com.example.llmapp.core.orchestrator.MemoryExtractionPlan {
        val lower = query.lowercase().trim()

        // Reject obvious transient states first
        val transientSignals = listOf("i am tired", "i'm tired", "i am hungry", "i'm hungry",
            "i am busy", "i'm busy", "i am bored", "i'm bored", "i feel", "today was",
            "should i", "can you", "what is", "how to", "how do")
        if (transientSignals.any { lower.contains(it) }) {
            return com.example.llmapp.core.orchestrator.MemoryExtractionPlan.DISABLED
        }

        // Check for strong personal-fact signals
        val factSignals = listOf(
            "my name is", "i am ", "i'm ", "i work", "i live", "i use ",
            "i prefer", "i like ", "i love ", "i hate ", "my favorite",
            "i have been", "i've been", "i study", "i'm learning",
            "remember that", "remember this", "save this", "save it",
            "note that", "don't forget"
        )
        if (factSignals.any { lower.contains(it) }) {
            return com.example.llmapp.core.orchestrator.MemoryExtractionPlan(
                enabled = true,
                confidence = 0.8f,
                reason = "heuristic_fallback"
            )
        }

        return com.example.llmapp.core.orchestrator.MemoryExtractionPlan.DISABLED
    }
}
