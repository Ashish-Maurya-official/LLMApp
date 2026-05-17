package com.example.llmapp.ui.settings.composables

import androidx.compose.runtime.*
import com.example.llmapp.core.settings.SettingsManager

@Composable
fun HardwareBackendSection(
    settingsManager: SettingsManager
) {
    var hardwareBackend by remember { mutableStateOf(settingsManager.hardwareBackend) }
    val backendOptions = listOf("Auto", "GPU", "CPU")

    SectionHeader("Hardware Backend")
    
    SettingsDropdown(
        selectedOption = hardwareBackend,
        options = backendOptions,
        onOptionSelected = { selected ->
            hardwareBackend = selected
            settingsManager.hardwareBackend = selected
        }
    )
}
