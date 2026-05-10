package com.example.llmapp.ui.state

import com.example.llmapp.ChatMessage

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val isLoadingModel: Boolean = false,
    val status: String = "Ready",
    val errorMessage: String? = null,
    val isVoiceModeActive: Boolean = false,
    val voiceState: VoiceState = VoiceState.IDLE,
    val partialTranscript: String = "",
    val activeBackend: String? = null
)

/**
 * A single segment of streaming content.
 * Keeping prose and table as separate types means each has its own identity
 * in the Compose tree (via key()), so prose before a table never recomposes
 * when a new table row is committed, and vice-versa.
 */
sealed class StreamingSegment {
    /**
     * Plain prose or non-table markdown.
     * [stableText] is the text stripped of any partial last line so MarkwonText
     * only re-renders when a complete line is committed.
     */
    data class Prose(val stableText: String) : StreamingSegment()

    /**
     * A markdown table.
     * [committedRows] is a List — every element is one raw markdown line
     * (header, separator, data rows). A new element is appended when `\n`
     * terminates a pipe-line, giving true per-row state granularity.
     * [partialRow] is the currently-typing incomplete row (no `\n` yet).
     */
    data class Table(
        val committedRows: List<String>,
        val partialRow: String = ""
    ) : StreamingSegment() {
        /** Build the full Markwon-compatible markdown for committed rows only. */
        val committedMarkdown: String
            get() = if (committedRows.isEmpty()) "" else committedRows.joinToString("\n") + "\n"
    }
}

/**
 * High-frequency state: only the live generating bubble reads this.
 * Kept separate so that stable past messages NEVER recompose during streaming.
 */
data class StreamingState(
    val rawContent: String = "",
    val visibleText: String = "",
    val segments: List<StreamingSegment> = emptyList(),
    val thoughts: List<String> = emptyList(),
    val actions: List<com.example.llmapp.AgentAction> = emptyList()
)

sealed class ChatIntent {
    data class SendMessage(val text: String) : ChatIntent()
    data class LoadModel(val path: String) : ChatIntent()
    data class ModelLoaded(val backend: String) : ChatIntent()
    data class SetError(val message: String) : ChatIntent()
    data class RestoreSession(val sessionId: String) : ChatIntent()
    object ClearHistory : ChatIntent()
    object ActivateVoiceMode : ChatIntent()
    object DeactivateVoiceMode : ChatIntent()
    data class SetVoiceState(val state: VoiceState) : ChatIntent()
    data class SetPartialTranscript(val text: String) : ChatIntent()
    data class DeleteSession(val sessionId: String) : ChatIntent()
    object StopGeneration : ChatIntent()
}

