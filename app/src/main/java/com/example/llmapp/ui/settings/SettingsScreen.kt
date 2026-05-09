package com.example.llmapp.ui.settings

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.core.models.ModelManager
import com.example.llmapp.core.models.LlmModelInfo
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    currentTheme: String,
    onThemeChanged: (String) -> Unit,
    openDrawer: () -> Unit
) {
    val context = LocalContext.current
    var maxTokens by remember { mutableFloatStateOf(settingsManager.maxTokens.toFloat()) }
    var temperature by remember { mutableFloatStateOf(settingsManager.temperature) }
    var topK by remember { mutableFloatStateOf(settingsManager.topK.toFloat()) }
    var systemPrompt by remember { mutableStateOf(settingsManager.systemPrompt) }
    var ttsSpeechRate by remember { mutableFloatStateOf(settingsManager.ttsSpeechRate) }
    var contextLimit by remember { mutableFloatStateOf(settingsManager.contextLimit.toFloat()) }
    var selectedVoiceName by remember { mutableStateOf(settingsManager.ttsVoiceName) }
    var language by remember { mutableStateOf(settingsManager.language) }
    var languageDropdownExpanded by remember { mutableStateOf(false) }
    val languages = listOf("English", "Hindi", "Bhojpuri")
    
    val modelManager = remember { ModelManager(context) }
    var downloadedModels by remember { mutableStateOf(modelManager.getDownloadedModels()) }
    var currentModelPath by remember { mutableStateOf(settingsManager.currentModelPath) }

    // Load available TTS voices
    var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsInstance: TextToSpeech? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val voices: Set<Voice>? = tts?.voices
                if (voices != null) {
                    val defaultLang = Locale.getDefault().language
                    availableVoices = voices
                        .filter { v: Voice -> v.locale.language == defaultLang || v.locale == Locale.US }
                        .sortedBy { v: Voice -> v.name }
                }
            }
        }
        ttsInstance = tts
        onDispose { tts?.shutdown() }
    }

    var voiceDropdownExpanded by remember { mutableStateOf(false) }
    val selectedVoiceLabel = availableVoices.find { v -> v.name == selectedVoiceName }?.name ?: "System Default"

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ─── System Prompt ───────────────────────────────────────────
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

            Spacer(Modifier.height(20.dp))

            // ─── Inference Parameters ────────────────────────────────────
            SectionHeader("Inference Parameters")

            Text("Max Tokens: ${maxTokens.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = maxTokens,
                onValueChange = { maxTokens = it; settingsManager.maxTokens = it.toInt() },
                valueRange = 128f..4096f,
                steps = 30
            )

            Text("Temperature: ${String.format("%.2f", temperature)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = temperature,
                onValueChange = { temperature = it; settingsManager.temperature = it },
                valueRange = 0.0f..2.0f
            )

            Text("Top K: ${topK.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = topK,
                onValueChange = { topK = it; settingsManager.topK = it.toInt() },
                valueRange = 1f..100f
            )

            Text("History Limit (Messages): ${contextLimit.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = contextLimit,
                onValueChange = { contextLimit = it; settingsManager.contextLimit = it.toInt() },
                valueRange = 1f..50f,
                steps = 48
            )

            Spacer(Modifier.height(20.dp))

            // ─── TTS / Voice ─────────────────────────────────────────────
            SectionHeader("Voice & Speech")
            
            Text("Language", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            
            ExposedDropdownMenuBox(
                expanded = languageDropdownExpanded,
                onExpandedChange = { languageDropdownExpanded = !languageDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = language,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageDropdownExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = languageDropdownExpanded,
                    onDismissRequest = { languageDropdownExpanded = false }
                ) {
                    languages.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang) },
                            onClick = {
                                language = lang
                                settingsManager.language = lang
                                languageDropdownExpanded = false
                            }
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))

            Text("Speech Rate: ${String.format("%.2f", ttsSpeechRate)}x", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = ttsSpeechRate,
                onValueChange = { ttsSpeechRate = it; settingsManager.ttsSpeechRate = it },
                valueRange = 0.5f..2.0f
            )

            Spacer(Modifier.height(8.dp))

            Text("TTS Voice", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = voiceDropdownExpanded,
                onExpandedChange = { if (ttsReady) voiceDropdownExpanded = !voiceDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = selectedVoiceLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select Voice") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(voiceDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = voiceDropdownExpanded,
                    onDismissRequest = { voiceDropdownExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("System Default") },
                        onClick = {
                            selectedVoiceName = ""
                            settingsManager.ttsVoiceName = ""
                            voiceDropdownExpanded = false
                        }
                    )
                    availableVoices.forEach { voice ->
                        DropdownMenuItem(
                            text = { Text(voice.name) },
                            onClick = {
                                selectedVoiceName = voice.name
                                settingsManager.ttsVoiceName = voice.name
                                voiceDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ─── App Theme ───────────────────────────────────────────────
            SectionHeader("App Theme")
            val themes = listOf("System", "Light", "Dark")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                themes.forEach { theme ->
                    FilterChip(
                        selected = currentTheme == theme,
                        onClick = { onThemeChanged(theme) },
                        label = { Text(theme) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ─── Model Management ────────────────────────────────────────
            SectionHeader("Model Management")
            Text("Download and switch between different LLM models. Note: Files are 1GB+.", style = MaterialTheme.typography.bodySmall)
            
            Spacer(Modifier.height(8.dp))
            
            modelManager.availableModels.forEach { model ->
                val isDownloaded = downloadedModels.any { it.name == model.fileName }
                val isSelected = currentModelPath.endsWith(model.fileName)
                
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, style = MaterialTheme.typography.titleSmall)
                                Text(model.description, style = MaterialTheme.typography.bodySmall)
                                Text("Size: ${model.size}", style = MaterialTheme.typography.labelSmall)
                            }
                            if (isDownloaded) {
                                Button(
                                    onClick = { 
                                        val path = modelManager.getModelPath(model.fileName)
                                        currentModelPath = path
                                        settingsManager.currentModelPath = path
                                    },
                                    enabled = !isSelected
                                ) {
                                    Text(if (isSelected) "Active" else "Switch")
                                }
                            } else {
                                OutlinedButton(onClick = { modelManager.downloadModel(model) }) {
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
}
