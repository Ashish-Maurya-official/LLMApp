package com.example.llmapp.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.models.ModelDownloader
import kotlinx.coroutines.launch

data class AvailableModel(
    val name: String,
    val description: String,
    val size: String,
    val url: String,
    val fileName: String
)

val curatedModels = listOf(
    AvailableModel(
        "Gemma 2B (GPU INT4)",
        "Google's official LLM optimized for fast Android GPU inference.",
        "1.35 GB",
        "https://huggingface.co/alexdlov/gemma-2b-it-gpu-int4.bin/resolve/main/gemma-2b-it-gpu-int4.bin",
        "gemma-2b-it-gpu-int4.bin"
    ),
    AvailableModel(
        "Gemma 2B (CPU INT4)",
        "Fallback model for older devices without strong Vulkan support.",
        "1.34 GB",
        "https://huggingface.co/rperuman/gemma-2b-it-cpu-int4.bin/resolve/main/gemma-2b-it-cpu-int4.bin",
        "gemma-2b-it-cpu-int4.bin"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(modelDownloader: ModelDownloader, onModelSelected: (String) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    var downloadedModels by remember { mutableStateOf(setOf<String>()) }

    // Check which models are already downloaded
    LaunchedEffect(Unit) {
        val downloaded = curatedModels.filter {
            modelDownloader.getDownloadedModelPath(it.fileName) != null
        }.map { it.name }.toSet()
        downloadedModels = downloaded
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Model Gallery") })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    "Download and manage AI models locally. No internet required for inference.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            items(curatedModels) { model ->
                val isDownloaded = downloadedModels.contains(model.name)
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDownloaded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (isDownloaded) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = model.description, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Size: ${model.size}", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            
                            if (isDownloaded) {
                                Button(onClick = {
                                    modelDownloader.getDownloadedModelPath(model.fileName)?.let { path ->
                                        onModelSelected(path)
                                    }
                                }) {
                                    Text("Load Model")
                                }
                            } else {
                                OutlinedButton(onClick = {
                                    modelDownloader.downloadModel(model.url, model.fileName)
                                    // In a real app, observe DownloadManager state instead of blind delay
                                }) {
                                    Icon(Icons.Default.Download, contentDescription = "Download")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
