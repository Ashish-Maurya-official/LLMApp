package com.example.llmapp.ui.chat.utils

import com.example.llmapp.ui.chat.state.WeatherDetails

/**
 * Stateful line-by-line streaming markdown parser.
 * Dynamically identifies both complete and unclosed (actively generating) code blocks,
 * alongside weather cards and prose content, making it highly robust for real-time streaming displays.
 */
fun parseMarkdownParts(text: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    val lines = text.split("\n")

    var inCodeBlock = false
    var codeLanguage = ""
    val currentCodeContent = StringBuilder()
    val currentPlainContent = StringBuilder()

    fun flushPlainContent() {
        val plainText = currentPlainContent.toString()
        if (plainText.isNotEmpty()) {
            parseWeatherAndText(plainText.trimEnd('\n'), parts)
            currentPlainContent.setLength(0)
        }
    }

    for (line in lines) {
        if (line.trim().startsWith("```")) {
            if (inCodeBlock) {
                // End of code block
                parts.add(MessagePart.Code(codeLanguage, currentCodeContent.toString().trimEnd('\n')))
                currentCodeContent.setLength(0)
                inCodeBlock = false
            } else {
                // Start of code block
                flushPlainContent()
                codeLanguage = line.trim().substring(3).trim()
                inCodeBlock = true
            }
        } else {
            if (inCodeBlock) {
                currentCodeContent.append(line).append("\n")
            } else {
                currentPlainContent.append(line).append("\n")
            }
        }
    }

    // Flush any remaining content
    if (inCodeBlock) {
        // This is an open/unclosed code block while generating!
        parts.add(MessagePart.Code(codeLanguage, currentCodeContent.toString().trimEnd('\n')))
    } else {
        flushPlainContent()
    }

    return parts.ifEmpty { listOf(MessagePart.PlainMarkdown(text)) }
}

private fun parseWeatherAndText(segment: String, parts: MutableList<MessagePart>) {
    val weatherRegex = Regex("\\[WEATHER_CARD:(\\{[\\s\\S]*?\\})\\]")
    var subIndex = 0
    weatherRegex.findAll(segment).forEach { match ->
        val textBefore = segment.substring(subIndex, match.range.first)
        if (textBefore.isNotBlank()) {
            parts.add(MessagePart.PlainMarkdown(textBefore))
        }
        
        try {
            val json = org.json.JSONObject(match.groupValues[1])
            val details = WeatherDetails(
                location = json.optString("location", "Unknown Location"),
                temp = json.optString("temp", "--"),
                condition = json.optString("condition", "Unknown"),
                humidity = json.optString("humidity").takeIf { it.isNotBlank() },
                wind = json.optString("wind").takeIf { it.isNotBlank() },
                high = json.optString("high").takeIf { it.isNotBlank() },
                low = json.optString("low").takeIf { it.isNotBlank() }
            )
            parts.add(MessagePart.Weather(details))
        } catch (e: Exception) {
            android.util.Log.e("MarkdownParser", "Failed to parse WEATHER_CARD JSON: ${e.message}")
            parts.add(MessagePart.PlainMarkdown(match.value))
        }
        subIndex = match.range.last + 1
    }
    if (subIndex < segment.length) {
        val remaining = segment.substring(subIndex)
        if (remaining.isNotBlank()) {
            parts.add(MessagePart.PlainMarkdown(remaining))
        }
    }
}
