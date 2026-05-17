package com.example.llmapp.ui.settings.composables

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.settings.SettingsManager

@Composable
fun SystemPromptSection(
    settingsManager: SettingsManager
) {
    var systemPrompt by remember { mutableStateOf(settingsManager.systemPrompt) }
    
    SectionHeader("System Prompt")
    
    OutlinedTextField(
        value = systemPrompt,
        onValueChange = {
            systemPrompt = it
            settingsManager.systemPrompt = it
        },
        modifier = Modifier.fillMaxWidth().height(120.dp),
        placeholder = { Text("Enter default behavior instructions...") }
    )
}
