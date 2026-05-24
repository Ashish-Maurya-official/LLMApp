package com.example.llmapp.core.history

import android.util.Log
import com.example.llmapp.core.database.CognitiveStateDao
import com.example.llmapp.core.database.MemoryEntity
import com.example.llmapp.core.inference.LlmInferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background pipeline that intelligently synthesizes chat turns into high-density 
 * memory assertions before inserting them into RAG, preventing database bloat.
 */
class MemoryConsolidator(
    private val scope: CoroutineScope,
    private val llmInferenceManager: LlmInferenceManager,
    private val cognitiveStateDao: CognitiveStateDao
) {
    // Queue of pending chat turns to analyze: Pair<UserText, LlmText>
    private val processingQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)

    // Common stop phrases that are definitely not worth embedding
    private val stopPhrases = setOf(
        "ok", "okay", "thanks", "thank you", "hello", "hi", "hey", 
        "goodbye", "bye", "yes", "no", "sure", "got it", "understood"
    )

    init {
        startWorker()
    }

    /**
     * Enqueue a completed chat turn (User prompt + AI Response) for background consolidation.
     */
    fun enqueueTurn(userText: String, llmText: String) {
        scope.launch {
            processingQueue.send(Pair(userText, llmText))
        }
    }

    private fun startWorker() {
        scope.launch(Dispatchers.Default) {
            for (turn in processingQueue) {
                try {
                    processTurn(turn.first, turn.second)
                    // Small yield to prevent thrashing
                    delay(500)
                } catch (e: Exception) {
                    Log.e("MemoryConsolidator", "Error consolidating memory: \${e.message}")
                }
            }
        }
    }

    private suspend fun processTurn(userText: String, llmText: String) {
        // GATE 1: Heuristic Filter
        val cleanUser = userText.trim()
        val wordCount = cleanUser.split(Regex("\\s+")).size
        
        if (wordCount < 4 && stopPhrases.any { cleanUser.equals(it, ignoreCase = true) }) {
            Log.d("MemoryConsolidator", "Skipping short chatter: '\$cleanUser'")
            return
        }

        // GATE 2: Orchestrator Semantic Extraction
        val extractionPrompt = """
            <start_of_turn>system
            You are the Memory Consolidator. Extract factual information or user preferences from the interaction.
            Output ONLY valid JSON. If there is no salient information, output {"salient": false}.
            SCHEMA:
            {
              "fact": "string",
              "confidence": float (0.0 to 1.0),
              "source": "conversation",
              "salient": true
            }
            <end_of_turn>
            <start_of_turn>user
            User: $userText
            Assistant: $llmText
            <end_of_turn>
            <start_of_turn>model
            {
        """.trimIndent()

        Log.d("MemoryConsolidator", "Extracting semantic salience in background...")
        // Use the lightweight Orchestrator for this background task
        val rawResult = llmInferenceManager.generateOrchestratorResponse(extractionPrompt).trim()
        val extractionResult = if (rawResult.startsWith("{")) rawResult else "{\n$rawResult"

        try {
            val json = org.json.JSONObject(extractionResult.replace(Regex("```json|```"), "").trim())
            if (!json.optBoolean("salient", false)) {
                Log.d("MemoryConsolidator", "LLM deemed interaction non-salient. Dropping.")
                return
            }
            
            val fact = json.optString("fact", "")
            if (fact.isBlank()) return
            
            Log.d("MemoryConsolidator", "Extracted Salient Memory: $fact")

            // GATE 3: RAG Ingestion
            val memoryEntity = MemoryEntity(
                sessionId = "consolidated_${System.currentTimeMillis()}",
                type = "semantic",
                content = json.toString(), // Store as JSON string
                trustZone = 2 // 2 = Agent Inferred
            )

            cognitiveStateDao.insertMemories(listOf(memoryEntity))
            Log.d("MemoryConsolidator", "Memory safely committed to RAG.")
        } catch (e: Exception) {
            Log.e("MemoryConsolidator", "Failed to parse or save semantic memory JSON: ${e.message}")
        }
    }
}
