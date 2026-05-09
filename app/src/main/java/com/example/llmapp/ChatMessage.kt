package com.example.llmapp

import androidx.compose.runtime.Immutable

@Immutable
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false
)
