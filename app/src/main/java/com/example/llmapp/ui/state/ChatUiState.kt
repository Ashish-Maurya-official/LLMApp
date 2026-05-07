package com.example.llmapp.ui.state

import com.example.llmapp.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoadingModel: Boolean = false,
    val status: String = "Ready",
    val errorMessage: String? = null
)

sealed class ChatIntent {
    data class SendMessage(val text: String) : ChatIntent()
    data class LoadModel(val path: String) : ChatIntent()
    object ModelLoaded : ChatIntent()
    data class SetError(val message: String) : ChatIntent()
    data class RestoreSession(val sessionId: String) : ChatIntent()
    object ClearHistory : ChatIntent()
}
