package com.example.llmapp.core.runtime

import androidx.compose.runtime.Immutable

@Immutable
data class CognitiveState(
    val activeGenerationId: String? = null,
    val phase: ExecutionPhase = ExecutionPhase.IDLE,
    val isSearching: Boolean = false,
    val currentQuery: String = "",
    val searchContext: String? = null
)

enum class ExecutionPhase {
    IDLE,
    PLANNING,
    RETRIEVING,
    GENERATING,
    ERROR
}
