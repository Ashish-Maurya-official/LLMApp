package com.example.llmapp

import androidx.compose.runtime.Immutable
import com.example.llmapp.core.runtime.ThoughtItem

@Immutable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val thoughts: List<ThoughtItem> = emptyList(),
    val rawContent: String = ""
)
