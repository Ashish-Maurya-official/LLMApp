package com.example.llmapp.core.runtime

import androidx.compose.runtime.Immutable

@Immutable
data class RuntimeConfig(
    val useCpuOnly: Boolean = false,
    val isDebugMode: Boolean = true,
    val maxTokens: Int = 2048,
    val currentSessionId: String = java.util.UUID.randomUUID().toString()
)
