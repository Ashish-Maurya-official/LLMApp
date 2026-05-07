package com.example.llmapp

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

enum class AiMode(
    val label: String,
    val inputHint: String,
    val instruction: String
) {
    Chat(
        "Chat",
        "Ask anything offline...",
        "Be a helpful offline AI assistant. Answer clearly, be practical, and ask for missing details only when needed."
    ),
    Summarize(
        "Summarize",
        "Paste text or ask what to summarize...",
        "Summarize the user's content into concise, high-signal notes. Preserve key facts, decisions, numbers, and action items."
    ),
    Rewrite(
        "Rewrite",
        "Paste text to rewrite...",
        "Rewrite the user's content. Make it clearer, polished, and natural while preserving the original meaning."
    ),
    Code(
        "Code",
        "Describe the code task...",
        "Act as a senior coding assistant. Give correct, runnable code where useful, explain tradeoffs briefly, and call out assumptions."
    ),
    Explain(
        "Explain",
        "Paste something to explain...",
        "Explain the topic step by step in simple language, then give a compact advanced summary."
    ),
    Translate(
        "Translate",
        "Paste text and target language...",
        "Translate accurately. Preserve formatting and meaning. If no target language is specified, ask for it."
    ),
    Plan(
        "Plan",
        "Describe the goal...",
        "Create a practical plan with phases, risks, and concrete next actions. Keep it realistic for an offline mobile AI app."
    )
}

class ChatViewModel : ViewModel() {
    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages

    private val _isGenerating = mutableStateOf(false)
    val isGenerating: State<Boolean> get() = _isGenerating

    private val _status = mutableStateOf("Ready")
    val status: State<String> get() = _status

    private val _lastStats = mutableStateOf("")
    val lastStats: State<String> get() = _lastStats

    private val _selectedMode = mutableStateOf(AiMode.Chat)
    val selectedMode: State<AiMode> get() = _selectedMode

    private val _workspaceContext = mutableStateOf("")
    val workspaceContext: State<String> get() = _workspaceContext

    private val _customSystemInstruction = mutableStateOf("")
    val customSystemInstruction: State<String> get() = _customSystemInstruction

    var includeRecentMessages = mutableStateOf(true)
        private set

    var recentMessageLimit = mutableStateOf(10)
        private set

    var includeWorkspaceContext = mutableStateOf(true)
        private set

    var workspaceContextLimit = mutableStateOf(12_000)
        private set

    var gpuLayers = mutableStateOf(-1)
        private set

    var contextSize = mutableStateOf(2048)
        private set

    var maxOutputTokens = mutableStateOf(512)
        private set

    var temperature = mutableStateOf(0.7f)
        private set

    var topK = mutableStateOf(40)
        private set

    var topP = mutableStateOf(0.9f)
        private set

    private var currentModelResponse = ""

    fun addMessage(message: ChatMessage) {
        _messages.add(message)
    }

    fun updateStatus(newStatus: String) {
        _status.value = newStatus
    }

    fun updateGenerationStats(stats: String) {
        _lastStats.value = stats
        _status.value = stats
    }

    fun updateMode(mode: AiMode) {
        _selectedMode.value = mode
    }

    fun updateWorkspaceContext(context: String) {
        _workspaceContext.value = context.take(workspaceContextLimit.value)
    }

    fun clearWorkspaceContext() {
        _workspaceContext.value = ""
    }

    fun updateCustomSystemInstruction(instruction: String) {
        _customSystemInstruction.value = instruction.take(4_000)
    }

    fun updateIncludeRecentMessages(enabled: Boolean) {
        includeRecentMessages.value = enabled
    }

    fun updateRecentMessageLimit(value: Int) {
        recentMessageLimit.value = value.coerceIn(0, 30)
    }

    fun updateIncludeWorkspaceContext(enabled: Boolean) {
        includeWorkspaceContext.value = enabled
    }

    fun updateWorkspaceContextLimit(value: Int) {
        workspaceContextLimit.value = value.coerceIn(2_000, 24_000)
        _workspaceContext.value = _workspaceContext.value.take(workspaceContextLimit.value)
    }

    fun updateGpuLayers(value: Int) {
        gpuLayers.value = value
    }

    fun updateContextSize(value: Int) {
        contextSize.value = value
    }

    fun updateMaxOutputTokens(value: Int) {
        maxOutputTokens.value = value
    }

    fun updateTemperature(value: Float) {
        temperature.value = value
    }

    fun updateTopK(value: Int) {
        topK.value = value
    }

    fun updateTopP(value: Float) {
        topP.value = value
    }

    fun resetSettings() {
        _selectedMode.value = AiMode.Chat
        _customSystemInstruction.value = ""
        includeRecentMessages.value = true
        recentMessageLimit.value = 10
        includeWorkspaceContext.value = true
        workspaceContextLimit.value = 12_000
        gpuLayers.value = -1
        contextSize.value = 2048
        maxOutputTokens.value = 512
        temperature.value = 0.7f
        topK.value = 40
        topP.value = 0.9f
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

        val modelPrompt = buildPrompt(text)
        addMessage(ChatMessage(text, isUser = true))
        _isGenerating.value = true
        _lastStats.value = ""
        onSend(modelPrompt)
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
        _lastStats.value = ""
        currentModelResponse = ""
    }

    private fun buildPrompt(userText: String): String {
        val mode = _selectedMode.value
        val recentMessages = if (includeRecentMessages.value && recentMessageLimit.value > 0) {
            _messages.takeLast(recentMessageLimit.value).joinToString("\n") { message ->
                val role = if (message.isUser) "User" else "Assistant"
                "$role: ${message.text}"
            }
        } else {
            ""
        }
        val contextBlock = _workspaceContext.value.trim()
            .takeIf { includeWorkspaceContext.value && it.isNotBlank() }
            ?.let {
            """
            Workspace context:
            $it
            """.trimIndent()
        }.orEmpty()
        val customInstruction = _customSystemInstruction.value.trim()
            .takeIf { it.isNotBlank() }
            ?.let { "User-defined behavior:\n$it" }
            .orEmpty()

        return """
            System:
            You are running fully offline on this Android device. Do not claim to browse the web, call cloud APIs, or access files unless the user pasted/imported their contents.
            Current mode: ${mode.label}
            ${mode.instruction}
            $customInstruction

            $contextBlock

            Recent conversation:
            $recentMessages

            User:
            $userText

            Assistant:
        """.trimIndent()
    }
}
