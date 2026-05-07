package com.example.llmapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmapp.core.inference.LlmInferenceManager
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
            is ChatIntent.ClearHistory -> _uiState.update { it.copy(messages = emptyList()) }
            is ChatIntent.LoadModel -> {
                _uiState.update { it.copy(status = "Loading model via MediaPipe GPU...") }
            }
            is ChatIntent.SetError -> {
                _uiState.update { it.copy(errorMessage = intent.message, isGenerating = false) }
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
        return "<start_of_turn>user\n$userText<end_of_turn>\n<start_of_turn>model\n"
    }
}
