package com.example.llmapp.core.cognition

/**
 * Holds the state of the current conversation interaction.
 */
data class ConversationContext(
    val sessionId: String,
    val userIntent: String = "UNKNOWN",
    val sentiment: String = "NEUTRAL",
    val recentTurns: List<String> = emptyList()
)
