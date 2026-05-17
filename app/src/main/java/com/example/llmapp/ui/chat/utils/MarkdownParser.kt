package com.example.llmapp.ui.chat.utils

fun parseMarkdownParts(text: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    val codeBlockRegex = Regex("```(\\w*)[\\r\\n]+([\\s\\S]*?)```")
    var lastIndex = 0
    codeBlockRegex.findAll(text).forEach { match ->
        val textBefore = text.substring(lastIndex, match.range.first)
        if (textBefore.isNotBlank()) parts.add(MessagePart.PlainMarkdown(textBefore))
        parts.add(MessagePart.Code(match.groupValues[1], match.groupValues[2]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex)
        if (remaining.isNotBlank()) parts.add(MessagePart.PlainMarkdown(remaining))
    }
    return parts.ifEmpty { listOf(MessagePart.PlainMarkdown(text)) }
}
