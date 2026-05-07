package com.example.llmapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmapp.core.history.ChatHistoryManager
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.state.ChatIntent
import com.example.llmapp.ui.state.ChatUiState
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
    
    // Store current sessionId
    private var currentSessionId: String = System.currentTimeMillis().toString()

    var llmInferenceManager: LlmInferenceManager? = null
        set(value) {
            field = value
            value?.let { manager ->
                viewModelScope.launch {
                    manager.outputFlow.collect { (token, done) ->
                        _uiState.update { state ->
                            val currentMessages = state.messages.toMutableList()
                            val lastIndex = currentMessages.size - 1
                            if (lastIndex >= 0 && !currentMessages[lastIndex].isUser) {
                                val updatedText = currentMessages[lastIndex].text + token
                                currentMessages[lastIndex] = currentMessages[lastIndex].copy(text = updatedText)
                            }
                            
                            if (done) {
                                // Save session when model finishes responding
                                historyManager?.saveSession(currentSessionId, currentMessages)
                            }
                            
                            state.copy(
                                messages = currentMessages,
                                isGenerating = !done
                            )
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
                val messages = historyManager?.loadSession(intent.sessionId) ?: emptyList()
                currentSessionId = intent.sessionId
                _uiState.update { it.copy(messages = messages, errorMessage = null) }
            }
        }
    }

    private fun handleSendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isGenerating) return

        val userMessage = ChatMessage(text, isUser = true)
        val assistantMessage = ChatMessage("", isUser = false)
        
        _uiState.update { state ->
            state.copy(
                messages = state.messages + listOf(userMessage, assistantMessage),
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
}
