package com.example.llmapp.core.voice.pipeline

import android.util.Log

/**
 * Tracks crucial latencies (VAD, TTFT, etc.) for performance monitoring.
 */
class LatencyTracker {
    private var vadStartTime: Long = 0
    private var requestStartTime: Long = 0

    fun onVadStart() {
        vadStartTime = System.currentTimeMillis()
    }

    fun onRequestSent() {
        requestStartTime = System.currentTimeMillis()
    }

    fun onFirstTokenReceived() {
        val ttft = System.currentTimeMillis() - requestStartTime
        Log.d("LatencyTracker", "Time To First Token (TTFT): ${ttft}ms")
    }
}
