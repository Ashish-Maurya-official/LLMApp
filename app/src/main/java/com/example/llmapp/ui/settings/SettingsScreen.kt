package com.example.llmapp.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    currentTheme: String,
    onThemeChanged: (String) -> Unit,
    openDrawer: () -> Unit
) {
    var maxTokens by remember { mutableFloatStateOf(settingsManager.maxTokens.toFloat()) }
    var temperature by remember { mutableFloatStateOf(settingsManager.temperature) }
    var topK by remember { mutableFloatStateOf(settingsManager.topK.toFloat()) }
    var systemPrompt by remember { mutableStateOf(settingsManager.systemPrompt) }

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            ) 
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("System Prompt", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { 
                    systemPrompt = it
                    settingsManager.systemPrompt = it
                },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Enter default behavior instructions...") }
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("Max Tokens: ${maxTokens.toInt()}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = maxTokens,
                onValueChange = { 
                    maxTokens = it
                    settingsManager.maxTokens = it.toInt() 
                },
                valueRange = 128f..4096f,
                steps = 30
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("Temperature: ${String.format("%.2f", temperature)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = temperature,
                onValueChange = { 
                    temperature = it
                    settingsManager.temperature = it 
                },
                valueRange = 0.0f..2.0f
            )

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))

            Text("Top K: ${topK.toInt()}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = topK,
                onValueChange = { 
                    topK = it
                    settingsManager.topK = it.toInt() 
                },
                valueRange = 1f..100f
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text("App Theme", style = MaterialTheme.typography.titleMedium)
            val themes = listOf("System", "Light", "Dark")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                themes.forEach { theme ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = currentTheme == theme,
                            onClick = { onThemeChanged(theme) }
                        )
                        Text(text = theme)
                    }
                }
            }
        }
    }
}
