package com.example.llmapp.core.telemetry

import android.util.Log

class EquilibriumMonitor {
    private val mutationTimestamps = mutableListOf<Long>()
    private val MUTATION_THRESHOLD = 3
    private val TIME_WINDOW_MS = 60_000L // 1 minute

    /**
     * Called whenever the agent modifies an existing MemoryEntity.
     * Returns false if the agent is in a hallucination/volatility loop,
     * meaning the OS should lock the database and force a cognitive cool-down.
     * Returns true if equilibrium is stable.
     */
    fun logMemoryMutation(): Boolean {
        val now = System.currentTimeMillis()
        mutationTimestamps.add(now)

        // Prune old timestamps outside the 1-minute window
        mutationTimestamps.removeAll { now - it > TIME_WINDOW_MS }

        if (mutationTimestamps.size > MUTATION_THRESHOLD) {
            Log.w("EquilibriumMonitor", "High cognitive volatility detected! ${mutationTimestamps.size} mutations in 1 min.")
            return false // Trigger Cool-down
        }
        
        return true
    }
}
