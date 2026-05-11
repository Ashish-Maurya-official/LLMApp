package com.example.llmapp.core.runtime

import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CognitiveTelemetry(
    private val scope: CoroutineScope,
    private val onTelemetryUpdated: (CognitiveEvent.SystemEvent.TelemetryUpdated) -> Unit
) {
    private var activeGenerationStartTime: Long = 0
    private var totalTokensGenerated: Int = 0
    private var currentKvTokens: Int = 0
    private val mutex = Mutex()
    private var telemetryJob: Job? = null

    // Heuristic sizes (assuming 4-byte float, 32 layers, 128 dim as a baseline proxy)
    // Actually, LiteRT model properties vary. We use a proxy multiplier to estimate pressure.
    private val BYTES_PER_TOKEN_ESTIMATE = 4 * 32 * 128L 

    fun start() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (true) {
                delay(1000)
                emitTelemetry()
            }
        }
    }

    fun stop() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    suspend fun onGenerationRequested() {
        mutex.withLock {
            activeGenerationStartTime = System.currentTimeMillis()
            totalTokensGenerated = 0
        }
    }

    suspend fun onTokenEmitted(token: String) {
        mutex.withLock {
            // Very rough heuristic: 4 chars per token
            val tokenCount = (token.length / 4).coerceAtLeast(1)
            totalTokensGenerated += tokenCount
            currentKvTokens += tokenCount
        }
    }

    suspend fun onConversationReset() {
        mutex.withLock {
            currentKvTokens = 0
        }
    }

    private suspend fun emitTelemetry() {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val durationSec = (now - activeGenerationStartTime) / 1000f
            val throughput = if (durationSec > 0 && totalTokensGenerated > 0) {
                totalTokensGenerated / durationSec
            } else 0f

            val kvCacheBytes = currentKvTokens * BYTES_PER_TOKEN_ESTIMATE
            val jniAllocated = Debug.getNativeHeapAllocatedSize()

            onTelemetryUpdated(
                CognitiveEvent.SystemEvent.TelemetryUpdated(
                    kvCacheBytes = kvCacheBytes,
                    jniHeapBytes = jniAllocated,
                    tokenThroughput = throughput,
                    queueDepth = 0, // Updated externally by the scheduler
                    droppedEvents = 0 // Updated externally by the scheduler
                )
            )
        }
    }
}
