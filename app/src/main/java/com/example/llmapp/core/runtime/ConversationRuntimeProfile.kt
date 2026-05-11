package com.example.llmapp.core.runtime

data class ConversationRuntimeProfile(
    val interruptionRatePerMin: Float = 0f,
    val averageLatencyMs: Float = 0f,
    val tokenRate10Turns: Float = 0f
)
