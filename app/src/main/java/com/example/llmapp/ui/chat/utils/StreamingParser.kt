package com.example.llmapp.ui.chat.utils

/**
 * Strips partial (still-being-typed) markdown table rows from the end of
 * streaming text so Markwon only re-renders when a complete row is committed.
 */
fun getStableStreamingText(text: String): String {
    if (text.endsWith("\n")) return text
    val lastNewline = text.lastIndexOf('\n')
    val lastLine = if (lastNewline == -1) text else text.substring(lastNewline + 1)
    return if (lastLine.trimStart().startsWith("|")) {
        if (lastNewline == -1) "" else text.substring(0, lastNewline + 1)
    } else {
        text
    }
}

/**
 * Splits streaming text into plain-text and live table segments.
 */
fun parseStreamingContent(text: String): List<StreamingPart> {
    val parts = mutableListOf<StreamingPart>()
    val lines = text.split("\n")
    val textEndsWithNewline = text.endsWith("\n")
    var plainBuffer = StringBuilder()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val isLastLine = i == lines.size - 1
        val isPartialLine = isLastLine && !textEndsWithNewline

        if (line.trim().startsWith("|")) {
            if (plainBuffer.isNotEmpty()) {
                val segment = plainBuffer.toString().trimEnd('\n')
                if (segment.isNotBlank()) parts.add(StreamingPart.PlainText(segment))
                plainBuffer = StringBuilder()
            }

            val completeRows = mutableListOf<String>()
            var partialRow: String? = null

            while (i < lines.size) {
                val tLine = lines[i]
                val tIsLast = i == lines.size - 1
                val tIsPartial = tIsLast && !textEndsWithNewline

                if (tLine.trim().startsWith("|")) {
                    if (tIsPartial) {
                        partialRow = tLine.trim()
                    } else {
                        completeRows.add(tLine)
                    }
                    i++
                } else {
                    break
                }
            }

            val headerLine = completeRows.firstOrNull()
            val headers = if (headerLine != null) parseTableRow(headerLine) else emptyList()

            if (headers.isNotEmpty()) {
                val sepIdx = completeRows.indexOfFirst { isSeparatorRow(it) }
                val dataRows = if (sepIdx >= 0) {
                    completeRows.drop(sepIdx + 1).map { parseTableRow(it) }
                } else emptyList()
                parts.add(StreamingPart.LiveTable(headers, dataRows, partialRow))
            } else if (partialRow != null) {
                plainBuffer.append(partialRow)
            }
        } else {
            if (!isPartialLine) {
                plainBuffer.append(line).append("\n")
            } else {
                plainBuffer.append(line)
            }
            i++
        }
    }

    if (plainBuffer.isNotEmpty()) {
        val segment = plainBuffer.toString().trimEnd('\n')
        if (segment.isNotBlank()) parts.add(StreamingPart.PlainText(segment))
    }

    return parts.ifEmpty { listOf(StreamingPart.PlainText(text)) }
}
