package com.example.llmapp.core.memory

import android.util.Log
import com.example.llmapp.ChatMessage
import com.example.llmapp.core.database.EpisodeEntity
import com.example.llmapp.core.database.MemoryDao
import com.example.llmapp.core.inference.LlmInferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Auto-generates episodic memory summaries from conversation segments.
 * Triggers every 20 turns OR 30 minutes, whichever comes first.
 *
 * Hierarchical: Episodes → Chapters → Books (chapters/books are future work).
 */
class EpisodeGenerator(
    private val llmInferenceManager: LlmInferenceManager,
    private val memoryDao: MemoryDao,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EpisodeGenerator"
        private const val TURNS_THRESHOLD = 20
        private const val TIME_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
    }

    private var turnCount = 0
    private var lastEpisodeTimestamp = System.currentTimeMillis()

    /**
     * Called after each completed conversation turn.
     * Checks if an episode should be generated based on turn count or time elapsed.
     */
    fun onTurnCompleted(sessionId: String, recentMessages: List<ChatMessage>) {
        turnCount++
        val elapsed = System.currentTimeMillis() - lastEpisodeTimestamp

        if (turnCount >= TURNS_THRESHOLD || elapsed > TIME_THRESHOLD_MS) {
            generateEpisode(sessionId, recentMessages)
            turnCount = 0
            lastEpisodeTimestamp = System.currentTimeMillis()
        }
    }

    /**
     * Force-generates an episode (e.g., on session end).
     */
    fun forceGenerate(sessionId: String, recentMessages: List<ChatMessage>) {
        if (turnCount > 3) { // Only if there's enough content
            generateEpisode(sessionId, recentMessages)
            turnCount = 0
            lastEpisodeTimestamp = System.currentTimeMillis()
        }
    }

    private fun generateEpisode(sessionId: String, messages: List<ChatMessage>) {
        scope.launch(Dispatchers.Default) {
            try {
                val transcript = messages.takeLast(TURNS_THRESHOLD).joinToString("\n") {
                    "${if (it.isUser) "User" else "AI"}: ${it.text.take(200)}"
                }

                val prompt = """
                    <start_of_turn>system
                    Summarize this conversation segment into a brief episode record.
                    Output ONLY valid JSON. Do not include markdown formatting.
                    SCHEMA: {"topic": "string", "outcome": "string", "keyFacts": ["string"]}
                    <end_of_turn>
                    <start_of_turn>user
                    $transcript
                    <end_of_turn>
                    <start_of_turn>model
                    {
                """.trimIndent()

                val result = llmInferenceManager.generateOrchestratorResponse(prompt)
                val cleanJson = if (result.trim().startsWith("{")) result else "{$result"
                val json = JSONObject(cleanJson.replace(Regex("```json|```"), "").trim())

                val topic = json.optString("topic", "Conversation")
                val outcome = json.optString("outcome", "")
                val keyFactsArray = json.optJSONArray("keyFacts")
                val keyFacts = if (keyFactsArray != null) {
                    List(keyFactsArray.length()) { keyFactsArray.getString(it) }
                } else emptyList()

                val episode = EpisodeEntity(
                    sessionId = sessionId,
                    topic = topic,
                    outcome = outcome,
                    keyFacts = keyFacts.joinToString("|"), // Simple delimiter for now
                    turnCount = messages.takeLast(TURNS_THRESHOLD).size
                )

                memoryDao.insertEpisode(episode)
                Log.d(TAG, "Episode generated: '$topic' (${keyFacts.size} key facts)")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate episode: ${e.message}")
            }
        }
    }

    /**
     * Resets turn counter (e.g., on session switch).
     */
    fun reset() {
        turnCount = 0
        lastEpisodeTimestamp = System.currentTimeMillis()
    }
}
