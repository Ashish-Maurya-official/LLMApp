package com.example.llmapp

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages

    private val _isGenerating = mutableStateOf(false)
    val isGenerating: State<Boolean> get() = _isGenerating

    private val _status = mutableStateOf("Ready")
    val status: State<String> get() = _status

    private var currentModelResponse = ""

    fun addMessage(message: ChatMessage) {
        _messages.add(message)
    }

    fun updateStatus(newStatus: String) {
        _status.value = newStatus
    }

    fun onTokenGenerated(token: String) {
        if (currentModelResponse.isEmpty()) {
            _messages.add(ChatMessage("", isUser = false))
        }
        currentModelResponse += token
        val lastIndex = _messages.size - 1
        if (lastIndex >= 0 && !_messages[lastIndex].isUser) {
            _messages[lastIndex] = _messages[lastIndex].copy(text = currentModelResponse)
        }
    }

    fun onGenerationComplete() {
        _isGenerating.value = false
        currentModelResponse = ""
    }

    fun sendMessage(text: String, onSend: (String) -> Unit) {
        if (text.isBlank() || _isGenerating.value) return
        
        addMessage(ChatMessage(text, isUser = true))
        _isGenerating.value = true
        onSend(text)
    }

    fun setError(error: String) {
        _status.value = "Error: $error"
        _messages.add(ChatMessage(error, isUser = false, isError = true))
        _isGenerating.value = false
    }

    fun clearMessages() {
        _messages.clear()
        _status.value = "Ready"
        _isGenerating.value = false
        currentModelResponse = ""
    }
}
