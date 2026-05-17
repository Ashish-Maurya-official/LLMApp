package com.example.llmapp.core.prompts.builders

/**
 * Builds compact instructions for rendering native weather cards.
 * Injected strictly when SearchType is WEATHER.
 */
object WeatherPromptBuilder {

    fun build(): String {
        val sb = StringBuilder()
        sb.append("  - WEATHER CARD RULE: You MUST output a beautifully structured native weather card on its own line using exactly this compact JSON format:\n")
        sb.append("    [WEATHER_CARD:{\"location\":\"<City, Country>\",\"temp\":\"<temp>\",\"condition\":\"<e.g. Sunny, Rainy, Cloudy, Stormy, Snowing>\",\"humidity\":\"<humidity>%\",\"wind\":\"<wind speed>\",\"high\":\"<high temp>\",\"low\":\"<low temp>\"}]\n")
        sb.append("    Ensure the JSON is completely valid, single-line, compact, and contains no newlines. Do not mention the raw WEATHER_CARD tag in your normal response text.\n")
        return sb.toString()
    }
}
