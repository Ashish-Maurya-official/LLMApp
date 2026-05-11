package com.example.llmapp.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class RuntimeHealthMonitor(
    private val scope: CoroutineScope,
    private val emitEvent: suspend (CognitiveEvent) -> Unit
) {
    // Heuristic base matches CognitiveTelemetry. BYTES_PER_TOKEN_ESTIMATE = 4 * 32 * 128L
    // Assuming max safe context is 2000 tokens.
    private val MAX_KV_BYTES = 2000L * 4 * 32 * 128L
    
    private var currentLevel = CognitiveEvent.DegradationLevel.NORMAL

    suspend fun onTelemetry(event: CognitiveEvent.SystemEvent.TelemetryUpdated) {
        val pressure = event.kvCacheBytes.toFloat() / MAX_KV_BYTES

        val targetLevel = when {
            pressure >= 0.9f -> CognitiveEvent.DegradationLevel.HARD_RESET
            pressure >= 0.8f -> CognitiveEvent.DegradationLevel.SUMMARIZE_CONTEXT
            pressure >= 0.7f -> CognitiveEvent.DegradationLevel.REDUCED_RETRIEVAL
            pressure >= 0.6f -> CognitiveEvent.DegradationLevel.BATCHING_INCREASED
            else -> CognitiveEvent.DegradationLevel.NORMAL
        }

        if (targetLevel != currentLevel) {
            // State changed, request degradation
            currentLevel = targetLevel
            emitEvent(CognitiveEvent.SystemEvent.DegradationRequested(targetLevel))
        }
    }
}
