package com.example.llmapp.ui.state

import com.example.llmapp.ChatMessage

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentGeneratingMessage: String = "",
    val isGenerating: Boolean = false,
    val isLoadingModel: Boolean = false,
    val status: String = "Ready",
    val errorMessage: String? = null,
    val isVoiceModeActive: Boolean = false,
    val voiceState: VoiceState = VoiceState.IDLE,
    val partialTranscript: String = ""
)

sealed class ChatIntent {
    data class SendMessage(val text: String) : ChatIntent()
    data class LoadModel(val path: String) : ChatIntent()
    object ModelLoaded : ChatIntent()
    data class SetError(val message: String) : ChatIntent()
    data class RestoreSession(val sessionId: String) : ChatIntent()
    object ClearHistory : ChatIntent()
    object ActivateVoiceMode : ChatIntent()
    object DeactivateVoiceMode : ChatIntent()
    data class SetVoiceState(val state: VoiceState) : ChatIntent()
    data class SetPartialTranscript(val text: String) : ChatIntent()
    data class DeleteSession(val sessionId: String) : ChatIntent()
}

