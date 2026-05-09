package com.example.llmapp

import androidx.compose.runtime.Immutable

data class AgentAction(
    val toolName: String,
    val query: String,
    val result: String? = null,
    val uiSources: String? = null
)

@Immutable
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val thoughts: List<String> = emptyList(),
    val actions: List<AgentAction> = emptyList(),
    val rawContent: String = ""
)
