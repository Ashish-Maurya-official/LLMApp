package com.example.llmapp.core.memory

import android.util.Log
import com.example.llmapp.core.database.MemoryDao
import com.example.llmapp.core.database.SemanticMemoryEntity
import com.example.llmapp.core.inference.LlmInferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

data class MemoryCandidate(
    val fact: String,
    val confidence: Float,
    val importance: Float,
    val category: String
)

class MemoryExtractor(
    private val memoryDao: MemoryDao,
    private val llmInferenceManager: LlmInferenceManager,
    private val settingsManager: com.example.llmapp.core.settings.SettingsManager? = null
) {
    companion object {
        private const val TAG = "MemoryExtractor"
    }

    private val extractionRunning = AtomicBoolean(false)

    /**
     * Entry point for background extraction.
     */
    suspend fun extractAndSaveAsync(userQuery: String, sessionId: String) {
        if (!shouldExtractMemory(userQuery)) {
            Log.d(TAG, "Skipping extraction: Gating conditions not met.")
            return
        }

        if (!extractionRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Skipping extraction: Extractor is currently running.")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                Log.d(TAG, "Starting memory extraction for query: \"$userQuery\"")
                
                // 1. Prompt Orchestrator for fact extraction (with retry for GPU contention)
                val prompt = buildExtractionPrompt(userQuery)
                var rawResponse: String? = null
                for (attempt in 1..2) {
                    try {
                        rawResponse = llmInferenceManager.generateRouterResponse(prompt)
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "Extraction attempt $attempt failed: ${e.message}")
                        if (attempt < 2) {
                            kotlinx.coroutines.delay(500) // Brief pause before retry
                        } else {
                            Log.e(TAG, "All extraction attempts failed. Aborting.")
                            return@withContext
                        }
                    }
                }
                if (rawResponse == null) return@withContext
                // The prompt pre-seeds with '[', so prepend it
                val responseJson = if (rawResponse.trimStart().startsWith("[")) rawResponse else "[$rawResponse"
                Log.d(TAG, "Raw extraction response: $responseJson")
                
                // 2. Parse JSON to Candidates
                val candidates = parseCandidates(responseJson)
                Log.d(TAG, "Parsed ${candidates.size} candidates from response")
                if (candidates.isEmpty()) {
                    Log.d(TAG, "No valid memories extracted.")
                    return@withContext
                }

                // 3. Deduplicate and Insert
                for (candidate in candidates) {
                    processCandidate(candidate, sessionId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during memory extraction", e)
        } finally {
            extractionRunning.set(false)
        }
    }

    private fun shouldExtractMemory(userQuery: String): Boolean {
        // We now rely on the Level 1 Orchestrator model for the semantic gating decision.
        // This is just a lightweight final sanity check to prevent impossible extractions.
        if (userQuery.length < 3) return false
        
        return true
    }

    private fun buildExtractionPrompt(userQuery: String): String {
        return """
            <start_of_turn>system
            Extract persistent facts about the user from their message.
            Output ONLY a JSON array. No markdown. No explanation.
            
            Persistent facts: name, age, occupation, location, skills, preferences, habits, projects.
            NOT persistent: emotions, temporary states, questions, greetings.
            
            Example input: "I'm a software engineer and I love Kotlin"
            Example output: [{"fact":"User is a software engineer","confidence":0.95,"importance":0.9,"category":"identity"},{"fact":"User loves Kotlin","confidence":0.9,"importance":0.7,"category":"preference"}]
            
            If no persistent facts exist, output: []
            <end_of_turn>
            <start_of_turn>user
            $userQuery
            <end_of_turn>
            <start_of_turn>model
            [
        """.trimIndent()
    }

    private fun parseCandidates(jsonStr: String): List<MemoryCandidate> {
        val candidates = mutableListOf<MemoryCandidate>()
        
        // Find the first '[' or '{' and last ']' or '}'
        val startIdx = jsonStr.indexOfFirst { it == '[' || it == '{' }
        val endIdx = jsonStr.indexOfLast { it == ']' || it == '}' }
        
        if (startIdx == -1 || endIdx == -1 || startIdx > endIdx) {
            Log.d(TAG, "No JSON array or object found in extraction response.")
            return candidates
        }
        
        val cleanJson = jsonStr.substring(startIdx, endIdx + 1).trim()
        
        try {
            val jsonArray = if (cleanJson.startsWith("[")) {
                org.json.JSONArray(cleanJson)
            } else if (cleanJson.startsWith("{")) {
                // Sometime models wrap arrays in objects
                val obj = JSONObject(cleanJson)
                val keys = obj.keys()
                if (keys.hasNext()) obj.getJSONArray(keys.next()) else org.json.JSONArray()
            } else {
                org.json.JSONArray("[]")
            }

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val fact = obj.optString("fact")
                if (fact.isNotBlank()) {
                    candidates.add(
                        MemoryCandidate(
                            fact = fact,
                            confidence = obj.optDouble("confidence", 0.5).toFloat(),
                            importance = obj.optDouble("importance", 0.5).toFloat(),
                            category = obj.optString("category", "general")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse extraction JSON: $cleanJson", e)
        }
        
        return candidates
    }

    private fun processCandidate(candidate: MemoryCandidate, sessionId: String) {
        if (candidate.confidence < 0.3f) {
            Log.d(TAG, "Candidate rejected (low confidence): ${candidate.fact}")
            return
        }

        // FTS Deduplication
        // We use FTS to find existing memories with similar words.
        // We do a basic word split to create a MATCH query for SQLite FTS.
        // SQLite FTS match requires terms, so we take the most prominent words.
        val safeTerms = candidate.fact.replace(Regex("[^a-zA-Z0-9 ]"), "").split(" ")
            .filter { it.length > 3 }
            .joinToString(" OR ")
            
        var duplicateId: Long? = null
        
        if (safeTerms.isNotBlank()) {
            val matches = memoryDao.searchSemanticFts(safeTerms, 3)
            for (match in matches) {
                // If FTS returns a very similar string (basic subset/superset check or high overlap)
                val matchWords = match.content.lowercase().split(" ").toSet()
                val candWords = candidate.fact.lowercase().split(" ").toSet()
                val intersection = matchWords.intersect(candWords)
                val overlap = intersection.size.toFloat() / maxOf(matchWords.size, candWords.size)
                
                if (overlap > 0.6f) {
                    duplicateId = match.id
                    Log.d(TAG, "Dedupe MATCH: '${candidate.fact}' overlaps '${match.content}'")
                    break
                }
            }
        }

        if (duplicateId != null) {
            // Update existing memory importance
            val newImportance = minOf(1.0f, candidate.importance * 1.2f) // Boost importance on repetition
            memoryDao.updateSemanticImportance(duplicateId, newImportance)
            Log.d(TAG, "Updated existing memory $duplicateId to importance $newImportance")
        } else {
            // Insert new memory
            val entity = SemanticMemoryEntity(
                lineageId = sessionId,
                content = candidate.fact,
                importanceScore = candidate.importance,
                confidenceScore = candidate.confidence,
                category = candidate.category,
                epistemicState = if (candidate.confidence > 0.8f) "PROBABLE" else "ASSUMED"
            )
            memoryDao.insertSemantic(entity)
            Log.d(TAG, "Inserted new memory: ${candidate.fact}")
            
            // Auto-fill explicit profile fields from inferred facts
            autoFillProfileField(candidate)
        }
    }
    
    /**
     * Bridges inferred memories to explicit profile fields.
     * When the user says "my name is Ashish", the fact is stored as a semantic memory
     * AND the Full Name field on the profile page is auto-populated.
     */
    private fun autoFillProfileField(candidate: MemoryCandidate) {
        if (settingsManager == null) return
        
        val factLower = candidate.fact.lowercase()
        
        // Name extraction: "User's name is Ashish Maurya", "User is Ashish", etc.
        if (candidate.category.lowercase() == "identity") {
            val isNameFact = factLower.contains("name is") || factLower.contains("named") || 
                factLower.contains("called") ||
                // "User is Ashish Maurya" — but NOT "User is a software engineer"
                (factLower.startsWith("user is ") && !factLower.contains(" a ") && 
                 !factLower.contains("works") && !factLower.contains("lives") && 
                 !factLower.contains("from") && !factLower.contains("prefers"))
            
            if (isNameFact) {
                val name = candidate.fact
                    .replace(Regex("(?i)user'?s? name is\\s*"), "")
                    .replace(Regex("(?i)user is named\\s*"), "")
                    .replace(Regex("(?i)user is called\\s*"), "")
                    .replace(Regex("(?i)user is\\s*"), "")
                    .replace(Regex("(?i)the user'?s? name is\\s*"), "")
                    .trim()
                    .removeSuffix(".")
                if (name.isNotBlank() && settingsManager.userName.isBlank()) {
                    settingsManager.userName = name
                    Log.d(TAG, "Auto-filled profile name: $name")
                }
            }
        }
        
        // Location extraction
        if ((candidate.category.lowercase() == "identity" || candidate.category.lowercase() == "location") &&
            (factLower.contains("lives in") || factLower.contains("located in") || factLower.contains("from"))) {
            val location = candidate.fact
                .replace(Regex("(?i)user lives in\\s*"), "")
                .replace(Regex("(?i)user is located in\\s*"), "")
                .replace(Regex("(?i)user is from\\s*"), "")
                .trim()
            if (location.isNotBlank() && settingsManager.userLocation.isBlank()) {
                settingsManager.userLocation = location
                Log.d(TAG, "Auto-filled profile location: $location")
            }
        }
    }
}
