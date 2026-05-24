package com.example.llmapp.ui.chat.state

import com.example.llmapp.ChatMessage
import com.example.llmapp.AgentAction

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatUiState(
    val isGenerating: Boolean = false,
    val isLoadingModel: Boolean = false,
    val status: String = "Ready",
    val errorMessage: String? = null,
    val fallbackMessage: String? = null,
    val fallbackErrorDetails: String? = null,
    val isVoiceModeActive: Boolean = false,
    val voiceState: VoiceState = VoiceState.IDLE,
    val partialTranscript: String = "",
    val activeBackend: String? = null,
    val isDictating: Boolean = false,
    val finalDictatedText: String? = null
)

data class WeatherDetails(
    val location: String,
    val temp: String,
    val condition: String,
    val humidity: String? = null,
    val wind: String? = null,
    val high: String? = null,
    val low: String? = null
)

sealed class StreamingSegment {
    data class Prose(val stableText: String) : StreamingSegment()
    data class Table(
        val committedRows: List<String>,
        val partialRow: String = ""
    ) : StreamingSegment() {
        val committedMarkdown: String
            get() = if (committedRows.isEmpty()) "" else committedRows.joinToString("\n") + "\n"
    }
    data class Weather(val details: WeatherDetails) : StreamingSegment()
}

data class StreamingState(
    val rawContent: String = "",
    val visibleText: String = "",
    val segments: List<StreamingSegment> = emptyList(),
    val thoughts: List<String> = emptyList(),
    val actions: List<AgentAction> = emptyList()
)
