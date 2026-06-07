package com.example.llmapp.ui.chat.state

sealed class ChatIntent {
    data class SendMessage(val text: String) : ChatIntent()
    data class LoadModel(val path: String, val isOrchestrator: Boolean) : ChatIntent()
    data class UnloadModel(val path: String, val isOrchestrator: Boolean) : ChatIntent()
    data class ModelLoaded(val backend: String) : ChatIntent()
    data class SetError(val message: String?) : ChatIntent()
    data class RestoreSession(val sessionId: String) : ChatIntent()
    object ClearHistory : ChatIntent()
    object ActivateVoiceMode : ChatIntent()
    object DeactivateVoiceMode : ChatIntent()
    data class SetVoiceState(val state: VoiceState) : ChatIntent()
    data class SetPartialTranscript(val text: String) : ChatIntent()
    data class DeleteSession(val sessionId: String) : ChatIntent()
    object StopGeneration : ChatIntent()
    object StartDictation : ChatIntent()
    object StopDictation : ChatIntent()
    object ClearDictatedText : ChatIntent()
}
