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
        data class GenerationRequested(val prompt: String, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class TokenEmitted(val token: String, val isDone: Boolean, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class GenerationComplete(override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class StopGeneration(override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
        data class Error(val error: CognitiveError, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : RuntimeEvent
    }

    // UI Events
    sealed interface UIEvent : CognitiveEvent {
        data class UserInput(val text: String, override val timestamp: Long = System.currentTimeMillis()) : UIEvent
    }

    // Tool Events
    sealed interface ToolEvent : CognitiveEvent {
        val generationId: String
        data class SearchRequested(val query: String, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : ToolEvent
        data class SearchCompleted(val results: String, override val generationId: String, override val timestamp: Long = System.currentTimeMillis()) : ToolEvent
    }
}
