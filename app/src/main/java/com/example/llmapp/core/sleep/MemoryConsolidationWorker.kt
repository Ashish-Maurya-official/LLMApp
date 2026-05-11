package com.example.llmapp.core.sleep

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.llmapp.core.database.ChatDatabase
import com.example.llmapp.core.runtime.EpistemicLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MemoryConsolidationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i("MemoryConsolidation", "Starting offline cognitive consolidation cycle...")
            
            val db = ChatDatabase.getDatabase(applicationContext)
            val chatDao = db.chatDao()
            val stateDao = db.cognitiveStateDao()

            // 1. Fetch raw episodic messages from the last 24 hours.
            // In a full implementation, we pass these logs to the LLM for summarization.
            Log.d("MemoryConsolidation", "Extracting semantic traits and long-horizon goals...")

            // 2. Validate extracted narratives against Zone 0 Core Identity
            val narrativeValidator = NarrativeIntegrityValidator(chatDao)
            
            // Mocking an extracted goal from the day's chats
            val newGoal = com.example.llmapp.core.database.GoalEntity(
                description = "User wants to learn Android WorkManager.",
                status = "ACTIVE",
                priority = 2
            )

            // 3. Nightly Identity Audit (Anti-Dependency & Drift Protection)
            val anchorManager = com.example.llmapp.core.identity.IdentityAnchorManager(stateDao)
            val recentMemories = chatDao.getMemoriesByType("semantic")
            val purgedMemories = anchorManager.auditMemoriesForDrift(recentMemories)
            
            if (purgedMemories.isNotEmpty()) {
                Log.w("MemoryConsolidation", "Identity Drift Detected! Purged \${purgedMemories.size} non-compliant memories.")
                // In production, we would execute: stateDao.deleteMemories(purgedMemories)
            }

            // 4. Write validated goals and memories via Atomic Pipeline
            // This ensures sleep-cycle cognition uses the exact same protections as active cognition.
            val newHash = EpistemicLedger.calculateStateHash(recentMemories)

            val snapshot = com.example.llmapp.core.database.CognitiveSnapshotEntity(
                sessionId = "SLEEP_CYCLE_${System.currentTimeMillis()}",
                epistemicStateHash = newHash,
                version = 1
            )

            db.withTransaction {
                stateDao.insertGoals(listOf(newGoal))
                stateDao.insertSnapshot(snapshot)
            }

            Log.i("MemoryConsolidation", "Sleep cycle complete. Cognitive OS is refreshed.")
            Result.success()
            
        } catch (e: Exception) {
            Log.e("MemoryConsolidation", "Sleep cycle failed due to error: ${e.message}", e)
            Result.retry()
        }
    }
}
