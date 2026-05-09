package com.example.llmapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmapp.core.history.ChatHistoryManager
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.state.ChatIntent
import com.example.llmapp.ui.state.ChatUiState
import com.example.llmapp.ui.state.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _themePreference = MutableStateFlow("System")
    val themePreference: StateFlow<String> = _themePreference.asStateFlow()

    var settingsManager: SettingsManager? = null
        set(value) {
            field = value
            value?.let {
                _themePreference.value = it.themePreference
            }
        }
    var historyManager: ChatHistoryManager? = null
        set(value) {
            field = value
            refreshSessions()
        }
    
    private val _sessionList = MutableStateFlow<List<String>>(emptyList())
    val sessionList: StateFlow<List<String>> = _sessionList.asStateFlow()

    fun refreshSessions() {
        viewModelScope.launch {
            _sessionList.value = historyManager?.getSessionIds()?.toList()?.sortedDescending() ?: emptyList()
        }
    }
    // Store current sessionId
    private var currentSessionId: String = System.currentTimeMillis().toString()

    /**
     * Set this from the UI layer (ChatScreen) to receive raw tokens as they are
     * emitted by the LLM — used by VoiceManager for real-time sentence-chunked TTS.
     */
    var onNewToken: ((token: String, done: Boolean) -> Unit)? = null

    var llmInferenceManager: LlmInferenceManager? = null
        set(value) {
            field = value
            value?.let { manager ->
                viewModelScope.launch {
                    manager.outputFlow.collect { (token, done) ->
                        // Forward raw token to VoiceManager for streaming TTS
                        onNewToken?.invoke(token, done)

                        var shouldSave = false
                        var messagesToSave: List<ChatMessage> = emptyList()

                        _uiState.update { state ->
                            val updatedGeneratingText = state.currentGeneratingMessage + token

                            if (done) {
                                // Move finished message to main list
                                val finalAssistantMessage = ChatMessage(updatedGeneratingText, isUser = false)
                                val updatedMessages = state.messages + listOf(finalAssistantMessage)
                                
                                shouldSave = true
                                messagesToSave = updatedMessages
                                
                                state.copy(
                                    messages = updatedMessages,
                                    currentGeneratingMessage = "",
                                    isGenerating = false
                                )
                            } else {
                                // Just append token to the temporary string
                                state.copy(
                                    currentGeneratingMessage = updatedGeneratingText,
                                    isGenerating = true
                                )
                            }
                        }
                        
                        if (shouldSave) {
                            viewModelScope.launch {
                                historyManager?.saveSession(currentSessionId, messagesToSave)
                                refreshSessions()
                            }
                        }
                    }
                }
            }
        }

    fun processIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> handleSendMessage(intent.text)
            is ChatIntent.ClearHistory -> {
                _uiState.update { it.copy(messages = emptyList()) }
                currentSessionId = System.currentTimeMillis().toString()
            }
            is ChatIntent.LoadModel -> {
                _uiState.update { it.copy(status = "Loading model via MediaPipe GPU...", isLoadingModel = true, errorMessage = null) }
            }
            is ChatIntent.ModelLoaded -> {
                _uiState.update { it.copy(status = "Ready", isLoadingModel = false) }
            }
            is ChatIntent.SetError -> {
                _uiState.update { it.copy(errorMessage = intent.message, isGenerating = false, isLoadingModel = false) }
            }
            is ChatIntent.RestoreSession -> {
                viewModelScope.launch {
                    val messages = historyManager?.loadSession(intent.sessionId) ?: emptyList()
                    currentSessionId = intent.sessionId
                    _uiState.update { it.copy(messages = messages, errorMessage = null) }
                }
            }
            is ChatIntent.ActivateVoiceMode -> {
                _uiState.update { it.copy(isVoiceModeActive = true, voiceState = VoiceState.LISTENING) }
            }
            is ChatIntent.DeactivateVoiceMode -> {
                _uiState.update { it.copy(isVoiceModeActive = false, voiceState = VoiceState.IDLE, partialTranscript = "") }
            }
            is ChatIntent.SetVoiceState -> {
                _uiState.update { it.copy(voiceState = intent.state) }
            }
            is ChatIntent.SetPartialTranscript -> {
                _uiState.update { it.copy(partialTranscript = intent.text) }
            }
            is ChatIntent.DeleteSession -> {
                viewModelScope.launch {
                    historyManager?.deleteSession(intent.sessionId)
                    refreshSessions()
                    if (currentSessionId == intent.sessionId) {
                        _uiState.update { it.copy(messages = emptyList()) }
                        currentSessionId = System.currentTimeMillis().toString()
                    }
                }
            }
        }
    }

    private fun handleSendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isGenerating) return

        val userMessage = ChatMessage(text, isUser = true)
        
        _uiState.update { state ->
            state.copy(
                messages = state.messages + listOf(userMessage),
                currentGeneratingMessage = "",
                isGenerating = true,
                errorMessage = null
            )
        }

        try {
            val modelPrompt = buildPrompt(text)
            llmInferenceManager?.generateResponseAsync(modelPrompt)
        } catch (e: Exception) {
            _uiState.update { it.copy(
                isGenerating = false,
                errorMessage = e.message ?: "Unknown error"
            )}
        }
    }

    private fun buildPrompt(userText: String): String {
        val systemPrompt = settingsManager?.systemPrompt ?: ""
        val sb = StringBuilder()

        // Optionally prepend system instructions
        if (systemPrompt.isNotBlank()) {
            sb.append("<start_of_turn>user\nSystem Instructions: $systemPrompt<end_of_turn>\n<start_of_turn>model\nUnderstood.<end_of_turn>\n")
        }

        // Append conversation history
        for (msg in _uiState.value.messages) {
            if (msg.text.isBlank()) continue // Skip empty messages (like the ones currently generating)
            if (msg.isUser) {
                sb.append("<start_of_turn>user\n${msg.text}<end_of_turn>\n")
            } else {
                sb.append("<start_of_turn>model\n${msg.text}<end_of_turn>\n")
            }
        }

        // Append current user prompt
        sb.append("<start_of_turn>user\n$userText<end_of_turn>\n<start_of_turn>model\n")
        return sb.toString()
    }
    
    fun updateTheme(theme: String) {
        settingsManager?.themePreference = theme
        _themePreference.value = theme
    }

    override fun onCleared() {
        super.onCleared()
        llmInferenceManager?.close()
    }
}
