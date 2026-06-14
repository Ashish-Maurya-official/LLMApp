package com.example.llmapp.core.routing

import android.util.Log
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.orchestrator.RoutingDecision
import com.example.llmapp.core.tools.ToolRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class ParsedIntent(
    val primaryIntent: String,
    val needMemory: Boolean,
    val needRag: Boolean,
    val needTools: Boolean,
    val toolName: String?,
    val toolQuery: String?,
    val needMemoryExtraction: Boolean,
    val confidence: Float,
    val reasoningConfidence: Float
)

/**
 * Semantic router using FunctionGemma 270M to classify user query intents.
 */
class FunctionGemmaRouter(
    private val llmInferenceManager: LlmInferenceManager,
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private const val TAG = "FunctionGemmaRouter"
        private const val CONFIDENCE_THRESHOLD = 0.7f
        private const val ROUTER_TIMEOUT_MS = 1000L
    }

    private val fastRoutes: Map<String, RoutingDecision> = buildFastRouteCache()

    private fun buildFastRouteCache(): Map<String, RoutingDecision> {
        val chatDirect = { RoutingDecision(intent = "greeting", confidence = 1.0f, reasoningConfidence = 1.0f) }
        return listOf(
            "hi", "hello", "hey", "yo", "sup",
            "good morning", "good afternoon", "good evening", "good night",
            "thanks", "thank you", "thx", "ty",
            "bye", "goodbye", "see you", "later",
            "ok", "okay", "sure", "yes", "no", "yeah", "nah",
            "how are you", "what's up", "whats up"
        ).associateWith { chatDirect() }
    }

    private fun normalizeQuery(query: String): String {
        return query
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    suspend fun route(userQuery: String): RoutingDecision {
        val normalized = normalizeQuery(userQuery)

        // Fast route cache
        fastRoutes[normalized]?.let { cached ->
            Log.d(TAG, "Fast route HIT: '$normalized' → ${cached.intent}")
            return cached
        }

        // Router model inference
        return try {
            withTimeout(ROUTER_TIMEOUT_MS) {
                val prompt = buildRouterPrompt(userQuery)
                val rawJson = llmInferenceManager.generateRouterResponse(prompt)
                Log.d(TAG, "Raw router output: $rawJson")

                val parsedIntent = parseRoutingJson(rawJson, userQuery)
                val decision = applyPolicyGate(parsedIntent, normalized, userQuery)

                // Confidence gate
                if (decision.confidence < CONFIDENCE_THRESHOLD) {
                    Log.d(TAG, "Low confidence (${decision.confidence}), applying heuristic override")
                    return@withTimeout applyPolicyGate(heuristicOverride(normalized, userQuery), normalized, userQuery)
                }

                decision
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Router timed out after ${ROUTER_TIMEOUT_MS}ms, falling back to heuristics")
            applyPolicyGate(heuristicOverride(normalized, userQuery), normalized, userQuery)
        } catch (e: Exception) {
            Log.e(TAG, "Router failed: ${e.message}", e)
            applyPolicyGate(heuristicOverride(normalized, userQuery), normalized, userQuery)
        }
    }

    // Prompt Builder

    private fun buildRouterPrompt(query: String): String {
        val manifest = toolRegistry.toolManifest()
        val currentTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        return """
            <start_of_turn>system
            You are a semantic routing engine. Your job is to understand user meaning and assign exactly one primary route.
            Do not match words directly unless they help confirm meaning.
            Think about the user's actual intent, context, and whether the request is:
            1. memory_recall — asking about saved/past personal info
            2. memory_extraction — giving new stable personal info worth saving
            3. tool_use — needs live data, external action, calculations, reminders, device actions
            4. rag — asks about uploaded documents or internal knowledge
            5. chat — general conversation, explanation, creative writing, opinions
            6. clarify — ambiguous inputs with no context (e.g. "Tell me about it")
            
            Current Date and Time: $currentTime

            Tool Scenarios (Available Tools):
            $manifest

            Schema:
            {
              "reason": "Explain your routing logic first",
              "primary_intent": "",
              "need_memory": false,
              "need_rag": false,
              "need_tools": false,
              "need_memory_extraction": false,
              "tool_name": null,
              "tool_query": null,
              "confidence": 0.0,
              "reasoning_confidence": 0.0
            }
            <end_of_turn>
            <start_of_turn>user
            $query
            <end_of_turn>
            <start_of_turn>model
            {
        """.trimIndent()
    }

    // JSON Parser

    private fun parseRoutingJson(rawJson: String, originalQuery: String): ParsedIntent {
        try {
            val cleanJson = if (rawJson.trim().startsWith("{")) rawJson else "{$rawJson"
            val finalJson = cleanJson.replace(Regex("```json|```"), "").trim()

            val obj = JSONObject(finalJson)
            
            val reason = obj.optString("reason", "")
            if (reason.isNotBlank()) {
                Log.d(TAG, "Router Reason: $reason")
            }

            var parsedToolName = if (obj.isNull("tool_name")) null else obj.optString("tool_name").takeIf { it.isNotBlank() && it != "null" }?.uppercase()
            val toolQuery = if (obj.isNull("tool_query")) null else obj.optString("tool_query").takeIf { it.isNotBlank() && it != "null" }
            var parsedIntent = obj.optString("primary_intent", obj.optString("intent", "chat"))
            
            // Auto-redirect weather to web search
            if (parsedToolName == "WEATHER") parsedToolName = "WEB_SEARCH"
            if (parsedIntent == "tool_weather" || parsedIntent == "tool_use" || parsedIntent == "tool") {
                parsedIntent = "tool_${parsedToolName?.lowercase() ?: "web_search"}"
            }

            return ParsedIntent(
                primaryIntent = parsedIntent,
                needMemory = obj.optBoolean("need_memory", false),
                needRag = obj.optBoolean("need_rag", false),
                needTools = obj.optBoolean("need_tools", false),
                toolName = parsedToolName,
                toolQuery = toolQuery,
                needMemoryExtraction = obj.optBoolean("need_memory_extraction", false),
                confidence = obj.optDouble("confidence", 0.5).toFloat(),
                reasoningConfidence = obj.optDouble("reasoning_confidence", 0.5).toFloat()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse router JSON: ${e.message}. Falling back to heuristic.", e)
            return heuristicOverride(normalizeQuery(originalQuery), originalQuery)
        }
    }

    // Policy Gate

    private fun applyPolicyGate(intent: ParsedIntent, normalizedQuery: String, originalQuery: String): RoutingDecision {
        var finalIntent = intent.primaryIntent
        var finalNeedMemory = intent.needMemory
        var finalNeedExtraction = intent.needMemoryExtraction
        var finalNeedTools = intent.needTools
        
        // Policy: question about user's stored preference -> memory_recall
        if (finalIntent == "chat" && (normalizedQuery.contains("what language do i") || normalizedQuery.contains("what do i prefer"))) {
            finalIntent = "memory_recall"
            finalNeedMemory = true
            Log.d(TAG, "Policy Gate: Upgraded chat to memory_recall")
        }

        // Policy: stable user preference -> memory_extraction
        if (finalIntent == "chat" && (normalizedQuery.contains("remember that i") || normalizedQuery.contains("my favorite"))) {
            finalIntent = "memory_extraction"
            finalNeedExtraction = true
            Log.d(TAG, "Policy Gate: Upgraded chat to memory_extraction")
        }

        // Policy: Map primary_intent to boolean flags if missing
        if (finalIntent == "memory_recall") finalNeedMemory = true
        if (finalIntent == "memory_extraction") finalNeedExtraction = true
        if (finalIntent.startsWith("tool_")) finalNeedTools = true

        return RoutingDecision(
            intent = finalIntent,
            needMemory = finalNeedMemory,
            needRag = intent.needRag,
            needTools = finalNeedTools,
            toolName = intent.toolName,
            toolQuery = intent.toolQuery ?: originalQuery,
            needMemoryExtraction = finalNeedExtraction,
            confidence = intent.confidence,
            reasoningConfidence = intent.reasoningConfidence
        )
    }

    // Heuristic Fallbacks

    private fun heuristicOverride(normalizedQuery: String, originalQuery: String): ParsedIntent {
        var memoryRecallScore = 0
        var memoryExtractionScore = 0
        var toolScore = 0
        var ragScore = 0

        // Memory Recall Buckets
        if (listOf("what did i", "what was my", "do you remember", "what s my name", "what project", "last time", "previously").any { normalizedQuery.contains(it) }) memoryRecallScore += 2

        // Memory Extraction Buckets
        if (listOf("my name is", "i am", "i work", "i live", "i use", "i prefer", "i like", "i enjoy", "i specialize").any { normalizedQuery.contains(it) }) memoryExtractionScore += 2
        
        // Exclude transient states
        if (listOf("i am tired", "i m tired", "i am hungry", "i m hungry", "i am busy", "i feel", "today was").any { normalizedQuery.contains(it) }) memoryExtractionScore = 0

        // Tool Buckets
        var detectedTool: String? = null
        if (listOf("search", "google", "weather", "temperature", "forecast", "yesterday", "today", "news").any { normalizedQuery.contains(it) }) {
            toolScore += 2
            detectedTool = "WEB_SEARCH"
        }
        if (listOf("remind me", "reminder", "set alarm").any { normalizedQuery.contains(it) }) {
            toolScore += 2
            detectedTool = "REMINDER"
        }
        if (listOf("calculate", "math").any { normalizedQuery.contains(it) }) {
            toolScore += 2
            detectedTool = "CALCULATOR"
        }
        if (listOf("flashlight", "torch").any { normalizedQuery.contains(it) }) {
            toolScore += 2
            detectedTool = "FLASHLIGHT"
        }

        // RAG Buckets
        if (listOf("in the file", "uploaded document", "pdf", "notes", "that report").any { normalizedQuery.contains(it) }) ragScore += 2

        val scores = mapOf(
            "memory_recall" to memoryRecallScore,
            "memory_extraction" to memoryExtractionScore,
            "tool" to toolScore,
            "rag" to ragScore
        )

        val maxScore = scores.values.maxOrNull() ?: 0
        val bestIntent = if (maxScore > 0) scores.entries.first { it.value == maxScore }.key else "chat"

        val finalIntent = if (bestIntent == "tool") "tool_${detectedTool?.lowercase() ?: "web_search"}" else bestIntent

        Log.d(TAG, "Meaning-based heuristic override: scored bestIntent=$bestIntent, finalIntent=$finalIntent")

        return ParsedIntent(
            primaryIntent = finalIntent,
            needMemory = bestIntent == "memory_recall",
            needRag = bestIntent == "rag",
            needTools = bestIntent == "tool",
            toolName = detectedTool,
            toolQuery = originalQuery,
            needMemoryExtraction = bestIntent == "memory_extraction",
            confidence = if (maxScore > 0) 0.75f else 0.6f,
            reasoningConfidence = 0.6f
        )
    }
}
