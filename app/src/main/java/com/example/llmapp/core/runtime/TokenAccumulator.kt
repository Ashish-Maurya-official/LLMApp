package com.example.llmapp.core.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TokenAccumulator(
    private val scope: CoroutineScope,
    private val emitCallback: suspend (String, Boolean, String) -> Unit
) {
    private val buffer = StringBuilder()
    private val mutex = Mutex()
    private var tickerJob: Job? = null
    private var currentGenId: String? = null
    private var isDoneFlag = false

    // Adaptive intervals
    private val minFlushIntervalMs = 16L
    private var maxFlushIntervalMs = 50L

    fun setDegradationLevel(level: CognitiveEvent.DegradationLevel) {
        maxFlushIntervalMs = if (level == CognitiveEvent.DegradationLevel.NORMAL) 50L else 200L
    }

    fun start() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(maxFlushIntervalMs)
                flush(force = false)
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
    }

    suspend fun onToken(token: String, isDone: Boolean, genId: String) {
        val shouldFlushInstantly: Boolean
        mutex.withLock {
            if (currentGenId != genId) {
                buffer.clear()
                currentGenId = genId
            }
            buffer.append(token)
            isDoneFlag = isDone

            // Flush instantly on sentence boundaries to aid TTS
            shouldFlushInstantly = token.contains('.') || token.contains('\n') || token.contains('!') || token.contains('?') || isDone
        }

        if (shouldFlushInstantly) {
            flush(force = true)
        }
    }

    private suspend fun flush(force: Boolean) {
        var chunkToEmit = ""
        var doneToEmit = false
        var genIdToEmit = ""

        mutex.withLock {
            if (buffer.isNotEmpty() || isDoneFlag) {
                chunkToEmit = buffer.toString()
                doneToEmit = isDoneFlag
                genIdToEmit = currentGenId ?: ""
                buffer.clear()
                isDoneFlag = false // Reset so we don't spam 'done' in ticker
            }
        }

        if (chunkToEmit.isNotEmpty() || doneToEmit) {
            emitCallback(chunkToEmit, doneToEmit, genIdToEmit)
        }
    }
}
