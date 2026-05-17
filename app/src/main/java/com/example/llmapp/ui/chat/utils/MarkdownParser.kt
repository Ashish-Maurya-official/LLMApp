package com.example.llmapp.ui.chat.utils

import com.example.llmapp.ui.chat.state.WeatherDetails

fun parseMarkdownParts(text: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    val codeBlockRegex = Regex("```(\\w*)[\\r\\n]+([\\s\\S]*?)```")
    var lastIndex = 0

    fun parseWeatherAndText(segment: String) {
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

    codeBlockRegex.findAll(text).forEach { match ->
        val textBefore = text.substring(lastIndex, match.range.first)
        if (textBefore.isNotBlank()) {
            parseWeatherAndText(textBefore)
        }
        parts.add(MessagePart.Code(match.groupValues[1], match.groupValues[2]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex)
        if (remaining.isNotBlank()) {
            parseWeatherAndText(remaining)
        }
    }
    return parts.ifEmpty { listOf(MessagePart.PlainMarkdown(text)) }
}
