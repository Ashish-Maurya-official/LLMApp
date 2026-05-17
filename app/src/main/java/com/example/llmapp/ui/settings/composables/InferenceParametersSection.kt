package com.example.llmapp.ui.settings.composables

import androidx.compose.runtime.*
import com.example.llmapp.core.settings.SettingsManager

@Composable
fun InferenceParametersSection(
    settingsManager: SettingsManager
) {
    var maxTokens by remember { mutableFloatStateOf(settingsManager.maxTokens.toFloat()) }
    var temperature by remember { mutableFloatStateOf(settingsManager.temperature) }
    var topK by remember { mutableFloatStateOf(settingsManager.topK.toFloat()) }
    var contextLimit by remember { mutableFloatStateOf(settingsManager.contextLimit.toFloat()) }

    SectionHeader("Inference Parameters")

    SettingsSlider(
        label = "Max Tokens",
        value = maxTokens,
        onValueChange = { 
            maxTokens = it
            settingsManager.maxTokens = it.toInt() 
        },
        valueRange = 128f..4096f,
        steps = 30,
        valueFormatter = { it.toInt().toString() }
    )

    SettingsSlider(
        label = "Temperature",
        value = temperature,
        onValueChange = { 
            temperature = it
            settingsManager.temperature = it 
        },
        valueRange = 0.0f..2.0f
    )

    SettingsSlider(
        label = "Top K",
        value = topK,
        onValueChange = { 
            topK = it
            settingsManager.topK = it.toInt() 
        },
        valueRange = 1f..100f,
        valueFormatter = { it.toInt().toString() }
    )

    SettingsSlider(
        label = "History Limit (Messages)",
        value = contextLimit,
        onValueChange = { 
            contextLimit = it
            settingsManager.contextLimit = it.toInt() 
        },
        valueRange = 1f..50f,
        steps = 48,
        valueFormatter = { it.toInt().toString() }
    )
}
