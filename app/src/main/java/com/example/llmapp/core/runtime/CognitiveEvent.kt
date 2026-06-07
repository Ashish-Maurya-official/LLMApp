package com.example.llmapp.core.runtime

sealed interface CognitiveEvent {
    val timestamp: Long

    enum class DegradationLevel {
        NORMAL,
        BATCHING_INCREASED,
        REDUCED_RETRIEVAL,
        SUMMARIZE_CONTEXT,
        HARD_RESET
    }

    enum class ThermalState {
        NORMAL, WARM, HOT, CRITICAL, RECOVERY
    }

    // System Events
    sealed interface SystemEvent : CognitiveEvent {
        data class ThermalStatusChanged(val state: ThermalState, override val timestamp: Long = System.currentTimeMillis()) : SystemEvent
        data class TelemetryUpdated(
            val kvCacheBytes: Long,
            val jniHeapBytes: Long,
            val tokenThroughput: Float,
            val queueDepth: Int,
            val droppedEvents: Int,
            override val timestamp: Long = System.currentTimeMillis()
        ) : SystemEvent
        data class DegradationRequested(val level: DegradationLevel, override val timestamp: Long = System.currentTimeMillis()) : SystemEvent
    }

    // Runtime Events
    sealed interface RuntimeEvent : CognitiveEvent {
        val generationId: String
        data class GenerationRequested(val rawQuery: String, val prompt: String, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class TokenEmitted(val token: String, val isDone: Boolean, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class GenerationComplete(override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class StopGeneration(override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class Error(val error: CognitiveError, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
    }

    // UI Events
    sealed interface UIEvent : CognitiveEvent {
        data class UserInput(val text: String, override val timestamp: Long = System.currentTimeMillis()) : UIEvent
    }

    // Tool Events removed — execution happens directly via CognitiveWorkers

    // Thought Events — typed cognitive thought lifecycle (replaces <thought> XML tags)
    sealed interface ThoughtEvent : CognitiveEvent {
        val generationId: String

        /** A new thought has started (e.g., "Planning response", "Searching memory") */
        data class ThoughtStarted(
            val id: String = java.util.UUID.randomUUID().toString(),
            val source: ThoughtSource,
            val title: String,
            override val generationId: String,
            override val timestamp: Long = System.currentTimeMillis()
        ) : ThoughtEvent

        /** An in-progress update to an existing thought (appended to updates list) */
        data class ThoughtUpdated(
            val id: String,
            val content: String,
            val progress: Float? = null,
            override val generationId: String,
            override val timestamp: Long = System.currentTimeMillis()
        ) : ThoughtEvent

        /** A thought has completed — final summary text */
        data class ThoughtCompleted(
            val id: String,
            val summary: String,
            override val generationId: String,
            override val timestamp: Long = System.currentTimeMillis()
        ) : ThoughtEvent
    }
}
