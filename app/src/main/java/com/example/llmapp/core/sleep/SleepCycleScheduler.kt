package com.example.llmapp.core.sleep

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SleepCycleScheduler {

    /**
     * Schedules the Cognitive OS Sleep Cycle.
     * The OS enforces these constraints aggressively: the device MUST be idle, 
     * plugged in, and have sufficient battery. This ensures the heavy LLM summarization 
     * runs exactly like human sleep — during rest periods, completely invisibly to the user.
     */
    fun scheduleSleepCycle(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val sleepRequest = PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(
            24, TimeUnit.HOURS // Run once a day during the optimal downtime
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "CognitiveSleepCycle",
            ExistingPeriodicWorkPolicy.KEEP,
            sleepRequest
        )
    }
}
