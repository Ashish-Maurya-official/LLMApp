package com.example.llmapp.core.prompts.models

/**
 * Data class encapsulating user preferences, spatial-temporal data,
 * and cognitive regulation parameters to compile system prompts cleanly.
 */
data class PromptContext(
    val systemPromptOverride: String = "",
    val userName: String = "",
    val userDob: String = "",
    val userLocation: String = "",
    val userBio: String = "",
    val activeDegradationLevel: com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel = com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.NORMAL,
    val contextLimit: Int = 10,
    val perMessageCap: Int = 1000
)
