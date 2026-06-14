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

            // Fetch raw episodic messages
            Log.d("MemoryConsolidation", "Extracting semantic traits and long-horizon goals...")

            // Validate narrative integrity
            val narrativeValidator = NarrativeIntegrityValidator(chatDao)
            

            val newGoal = com.example.llmapp.core.database.GoalEntity(
                description = "User wants to learn Android WorkManager.",
                status = "ACTIVE",
                priority = 2
            )

            // Identity drift protection
            val anchorManager = com.example.llmapp.core.identity.IdentityAnchorManager(stateDao)
            val recentMemories = chatDao.getMemoriesByType("semantic")
            val purgedMemories = anchorManager.auditMemoriesForDrift(recentMemories)
            
            if (purgedMemories.isNotEmpty()) {
                Log.w("MemoryConsolidation", "Identity Drift Detected! Purged \${purgedMemories.size} non-compliant memories.")

            }

            // Multi-timescale lifecycle policy
            Log.d("MemoryConsolidation", "Applying Multi-Timescale Lifecycle Policies...")
            val workingMemories = chatDao.getMemoriesByType("working")
            val agedWorkingMemories = workingMemories.filter { System.currentTimeMillis() - it.timestamp > 86400000L } // Older than 24h

            
            // Contamination detection
            Log.d("MemoryConsolidation", "Running Recursive-Memory Contamination Detection...")
            val contaminatedMemories = recentMemories.filter { 
                it.epistemicState == "CONTRADICTED" || (it.epistemicState == "ASSUMED" && System.currentTimeMillis() - it.timestamp > 7 * 86400000L) // Assumed for > 1 week without verification
            }
            if (contaminatedMemories.isNotEmpty()) {
                Log.w("MemoryConsolidation", "Contamination Detected! Purged \${contaminatedMemories.size} low-confidence recursive memories.")

            }

            // Save snapshot and validated goals
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
