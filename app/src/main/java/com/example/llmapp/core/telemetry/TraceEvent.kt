package com.example.llmapp.core.telemetry

sealed class TraceEvent {
    abstract val timestamp: Long
    abstract val generationId: String?

    data class StateTransition(
        override val timestamp: Long = System.currentTimeMillis(),
        override val generationId: String?,
        val fromPhase: String,
        val toPhase: String,
        val triggerReason: String
    ) : TraceEvent()

    data class TaskArbitration(
        override val timestamp: Long = System.currentTimeMillis(),
        override val generationId: String?,
        val decision: String,
        val preemptedTask: String? = null
    ) : TraceEvent()

    data class ThermalThrottle(
        override val timestamp: Long = System.currentTimeMillis(),
        override val generationId: String?,
        val severity: Int, // 1 = WARM, 2 = HOT, 3 = CRITICAL
        val actionTaken: String
    ) : TraceEvent()

    data class MemoryCommit(
        override val timestamp: Long = System.currentTimeMillis(),
        override val generationId: String?,
        val memoriesInserted: Int,
        val epistemicHash: String
    ) : TraceEvent()

    data class Error(
        override val timestamp: Long = System.currentTimeMillis(),
        override val generationId: String?,
        val errorType: String,
        val message: String
    ) : TraceEvent()
}
