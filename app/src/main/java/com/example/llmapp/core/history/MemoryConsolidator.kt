package com.example.llmapp.core.history

import android.util.Log
import com.example.llmapp.core.database.CognitiveStateDao
import com.example.llmapp.core.database.MemoryDao
import com.example.llmapp.core.database.MemoryEntity
import com.example.llmapp.core.database.ProfileMemoryEntity
import com.example.llmapp.core.database.SemanticMemoryEntity
import com.example.llmapp.core.inference.LlmInferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background pipeline that intelligently synthesizes chat turns into high-density 
 * memory assertions, classifying them by type (PROFILE/SEMANTIC) with importance
 * scores before inserting into the appropriate memory store.
 */
class MemoryConsolidator(
    private val scope: CoroutineScope,
    private val llmInferenceManager: LlmInferenceManager,
    private val cognitiveStateDao: CognitiveStateDao,
    private var memoryDao: MemoryDao? = null
) {
    // Queue of pending chat turns to analyze: Pair<UserText, LlmText>
    private val processingQueue = Channel<Pair<String, String>>(Channel.UNLIMITED)

    // Common stop phrases that are definitely not worth embedding
    private val stopPhrases = setOf(
        "ok", "okay", "thanks", "thank you", "hello", "hi", "hey", 
        "goodbye", "bye", "yes", "no", "sure", "got it", "understood"
    )

    // Called after new memories are written to invalidate retrieval cache
    var onMemoryWritten: (() -> Unit)? = null

    init {
        startWorker()
    }

    fun setMemoryDao(dao: MemoryDao) {
        this.memoryDao = dao
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
                    Log.e("MemoryConsolidator", "Error consolidating memory: ${e.message}")
                }
            }
        }
    }

    private suspend fun processTurn(userText: String, llmText: String) {
        // GATE 1: Heuristic Filter
        val cleanUser = userText.trim()
        val wordCount = cleanUser.split(Regex("\\s+")).size
        
        if (wordCount < 4 && stopPhrases.any { cleanUser.equals(it, ignoreCase = true) }) {
            Log.d("MemoryConsolidator", "Skipping short chatter: '$cleanUser'")
            return
        }

        // GATE 2: Orchestrator Semantic Extraction with type + importance classification
        val extractionPrompt = """
            <start_of_turn>system
            You are the Memory Consolidator. Extract factual information or user preferences from the interaction.
            Output ONLY valid JSON. If there is no salient information, output {"salient": false}.
            
            Classify the type:
            - PROFILE: User identity info (name, DOB, location, preferences, pets, job)
            - SEMANTIC: Learned facts, technical details, project info
            
            Set importance score:
            - 1.0: Identity (name, DOB, location)
            - 0.95: Career/education
            - 0.9: Long-term projects/goals
            - 0.7: Preferences/opinions
            - 0.5: Skills/technologies used
            - 0.3: Casual interests
            - 0.05: Small talk/greetings
            
            For PROFILE type, also set "key" (lowercase, snake_case identifier like "name", "location", "pet_name").
            
            SCHEMA:
            {
              "fact": "string",
              "type": "PROFILE | SEMANTIC",
              "key": "string (for PROFILE only)",
              "importance": float (0.0 to 1.0),
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
        val rawResult = llmInferenceManager.generateRouterResponse(extractionPrompt).trim()
        val extractionResult = if (rawResult.startsWith("{")) rawResult else "{\n$rawResult"

        try {
            val json = org.json.JSONObject(extractionResult.replace(Regex("```json|```"), "").trim())
            if (!json.optBoolean("salient", false)) {
                Log.d("MemoryConsolidator", "LLM deemed interaction non-salient. Dropping.")
                return
            }
            
            val fact = json.optString("fact", "")
            if (fact.isBlank()) return
            
            val type = json.optString("type", "SEMANTIC").uppercase()
            val importance = json.optDouble("importance", 0.5).toFloat()
            val key = json.optString("key", "unknown")
            
            Log.d("MemoryConsolidator", "Extracted: [$type] $fact (importance=$importance)")

            // Write to new typed stores via MemoryDao
            val mDao = memoryDao
            if (mDao != null) {
                when (type) {
                    "PROFILE" -> {
                        // Expire old value for this key before inserting new one
                        mDao.expireProfile(key)
                        mDao.insertProfile(ProfileMemoryEntity(
                            key = key,
                            value = fact,
                            importanceScore = importance,
                            epistemicState = "ASSUMED",
                            lineageId = "consolidated_${System.currentTimeMillis()}"
                        ))
                        Log.d("MemoryConsolidator", "Profile memory committed: $key → $fact")
                    }
                    else -> {
                        // Check for duplicate before inserting
                        val existing = mDao.getSemanticByExactContent(fact)
                        if (existing == null) {
                            mDao.insertSemantic(SemanticMemoryEntity(
                                content = fact,
                                importanceScore = importance,
                                epistemicState = "ASSUMED",
                                lineageId = "consolidated_${System.currentTimeMillis()}"
                            ))
                            Log.d("MemoryConsolidator", "Semantic memory committed: $fact")
                        } else {
                            Log.d("MemoryConsolidator", "Duplicate semantic memory skipped: $fact")
                        }
                    }
                }
                // Notify MemoryAgent to invalidate cache
                onMemoryWritten?.invoke()
            }

            // Legacy: also write to old MemoryEntity table for backward compatibility
            val memoryEntity = MemoryEntity(
                sessionId = "consolidated_${System.currentTimeMillis()}",
                type = type.lowercase(),
                content = json.toString(),
                trustZone = 2
            )
            cognitiveStateDao.insertMemories(listOf(memoryEntity))
            
        } catch (e: Exception) {
            Log.e("MemoryConsolidator", "Failed to parse or save semantic memory JSON: ${e.message}")
        }
    }
}

