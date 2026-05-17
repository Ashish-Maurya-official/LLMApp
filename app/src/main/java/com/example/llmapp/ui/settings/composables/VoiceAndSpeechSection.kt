package com.example.llmapp.ui.settings.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.settings.utils.rememberTtsVoices

@Composable
fun VoiceAndSpeechSection(
    settingsManager: SettingsManager
) {
    val context = LocalContext.current
    var ttsSpeechRate by remember { mutableFloatStateOf(settingsManager.ttsSpeechRate) }
    var selectedVoiceName by remember { mutableStateOf(settingsManager.ttsVoiceName) }
    var language by remember { mutableStateOf(settingsManager.language) }
    val languages = listOf("English", "Hindi", "Bhojpuri")

    // Load available TTS voices using extracted utility hook
    val ttsState = rememberTtsVoices(context)

    val selectedVoiceLabel = ttsState.availableVoices.find { it.name == selectedVoiceName }?.name ?: "System Default"

    SectionHeader("Voice & Speech")
    
    Text("Language", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))
    
    SettingsDropdown(
        selectedOption = language,
        options = languages,
        onOptionSelected = {
            language = it
            settingsManager.language = it
        }
    )
    
    Spacer(Modifier.height(8.dp))

    SettingsSlider(
        label = "Speech Rate",
        value = ttsSpeechRate,
        onValueChange = { 
            ttsSpeechRate = it
            settingsManager.ttsSpeechRate = it 
        },
        valueRange = 0.5f..2.0f,
        valueFormatter = { String.format("%.2fx", it) }
    )

    Spacer(Modifier.height(8.dp))

    Text("TTS Voice", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(4.dp))

    val voiceOptions = listOf("System Default") + ttsState.availableVoices.map { it.name }
    val displaySelectedVoice = if (selectedVoiceName.isEmpty()) "System Default" else selectedVoiceName

    SettingsDropdown(
        selectedOption = displaySelectedVoice,
        options = voiceOptions,
        onOptionSelected = { selection ->
            val finalName = if (selection == "System Default") "" else selection
            selectedVoiceName = finalName
            settingsManager.ttsVoiceName = finalName
        }
    )
}
