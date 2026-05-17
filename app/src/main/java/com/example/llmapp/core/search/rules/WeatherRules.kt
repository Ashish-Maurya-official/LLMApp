package com.example.llmapp.core.search.rules

import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.classification.SearchRule

/**
 * Isolated classification rules for weather-related queries.
 */
object WeatherRules {
    val rule = SearchRule(
        type = SearchType.WEATHER,
        keywords = setOf(
            "weather", "temperature", "temp", "forecast", "rain", "sunny", "humidity",
            "wind", "climate", "degree", "snow", "storm", "wheather", "wether"
        ),
        confidence = 0.95f,
        description = "Triggers weather status and local forecasts."
    )
}
