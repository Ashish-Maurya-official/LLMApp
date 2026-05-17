package com.example.llmapp.ui.chat.utils

sealed class MessagePart {
    data class PlainMarkdown(val content: String) : MessagePart()
    data class Code(val language: String, val content: String) : MessagePart()
}

sealed class StreamingPart {
    data class PlainText(val text: String) : StreamingPart()
    data class LiveTable(
        val headers: List<String>,
        val rows: List<List<String>>,
        val partialRow: String? = null
    ) : StreamingPart()
}
