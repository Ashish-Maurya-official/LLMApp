package com.example.llmapp.ui.models

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.core.models.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

data class AvailableModel(
    val name: String,
    val description: String,
    val size: String,
    val url: String,
    val fileName: String,
    val tags: List<String> = emptyList()
)

/** Fallback catalog used when the remote manifest cannot be reached */
val fallbackModels = listOf(
    AvailableModel(
        "Gemma 4 E2B (IT)",
        "The absolute latest generation (April 2026). Optimized for LiteRT-LM with top reasoning.",
        "1.8 GB",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        "gemma-4-e2b.litertlm",
        listOf("LiteRT", "NEW", "2026")
    ),
    AvailableModel(
        "Gemma 2 2B (IT)",
        "Verified high-performance model for mobile. Fast, stable, and smart.",
        "1.4 GB",
        "https://huggingface.co/litert-community/gemma-2-2b-it-litert-lm/resolve/main/gemma-2-2b-it.litertlm",
        "gemma-2-2b.litertlm",
        listOf("LiteRT", "Stable")
    ),
    AvailableModel(
        "Falcon 1B",
        "Ultra-lightweight model. Works on any device without lag.",
        "0.9 GB",
        "https://huggingface.co/tiiuae/falcon-1b-it-gpu-int4/resolve/main/falcon-1b-it-gpu-int4.bin",
        "falcon-1b-it.bin",
        listOf("Lightweight")
    )
)

/** Attempts to fetch a remote JSON manifest, returns null on any failure */
suspend fun fetchRemoteModels(): List<AvailableModel>? = withContext(Dispatchers.IO) {
    try {
        val json = URL("https://raw.githubusercontent.com/Ashish-Maurya-official/LLMApp/main/models.json")
            .readText(Charsets.UTF_8)
        val arr = JSONArray(json)
        val result = mutableListOf<AvailableModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val tagsArr = obj.optJSONArray("tags")
            val tags = if (tagsArr != null) List(tagsArr.length()) { tagsArr.getString(it) } else emptyList()
            result.add(
                AvailableModel(
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    size = obj.getString("size"),
                    url = obj.getString("url"),
                    fileName = obj.getString("fileName"),
                    tags = tags
                )
            )
        }
        result
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    modelDownloader: ModelDownloader,
    onModelSelected: (String) -> Unit,
    openDrawer: () -> Unit,
    onOpenEvaluation: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var models by remember { mutableStateOf(fallbackModels) }
    var isLoadingCatalog by remember { mutableStateOf(true) }
    var catalogSource by remember { mutableStateOf("") }

    // Track per-model download progress (0..1), null = not downloading
    val downloadProgress = remember { mutableStateMapOf<String, Float>() }

    // Reactive set of downloaded model names — updated explicitly after each download completes
    var downloadedModels by remember { mutableStateOf(emptySet<String>()) }

    // Helper to re-scan disk and refresh the set
    fun refreshDownloadedModels(currentModels: List<AvailableModel>) {
        downloadedModels = currentModels
            .filter { modelDownloader.getDownloadedModelPath(it.fileName) != null }
            .map { it.name }
            .toSet()
    }

    // Fetch remote catalog on launch
    LaunchedEffect(Unit) {
        isLoadingCatalog = true

        // Step 1: Scan disk IMMEDIATELY with the fallback models so the correct
        // "Load Model" / "Download" state is visible on the very first frame.
        downloadedModels = withContext(kotlinx.coroutines.Dispatchers.IO) {
            fallbackModels.filter { modelDownloader.getDownloadedModelPath(it.fileName) != null }
                .map { it.name }.toSet()
        }

        // Step 2: Fetch the remote catalog in the background (this takes a few seconds)
        val remote = withContext(kotlinx.coroutines.Dispatchers.IO) { fetchRemoteModels() }
        val combined = if (remote != null) {
            val merged = (fallbackModels + remote).distinctBy { it.fileName }
            catalogSource = "Remote catalog synced"
            merged
        } else {
            catalogSource = "Offline — using local catalog"
            fallbackModels
        }
        models = combined

        // Step 3: Rescan disk now that we may have extra models from the remote catalog
        downloadedModels = withContext(kotlinx.coroutines.Dispatchers.IO) {
            combined.filter { modelDownloader.getDownloadedModelPath(it.fileName) != null }
                .map { it.name }.toSet()
        }
        isLoadingCatalog = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Gallery") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "Download and run AI models completely offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLoadingCatalog) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Fetching latest models...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else if (catalogSource.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(catalogSource, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onOpenEvaluation,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Run Cognitive Benchmark (Phase 7)")
                    }
                }
            }

            items(models, key = { it.fileName }) { model ->
                val isDownloaded = downloadedModels.contains(model.name)
                val progress = downloadProgress[model.fileName]
                val isDownloading = progress != null && progress < 1.0f
                val animatedProgress by animateFloatAsState(
                    targetValue = progress ?: 0f,
                    label = "download_progress"
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when {
                            isDownloaded -> MaterialTheme.colorScheme.secondaryContainer
                            isDownloading -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Header row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Size: ${model.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isDownloaded) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        // Tags
                        if (model.tags.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                model.tags.forEach { tag ->
                                    Surface(
                                        shape = MaterialTheme.shapes.extraSmall,
                                        color = MaterialTheme.colorScheme.tertiaryContainer
                                    ) {
                                        Text(
                                            tag,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = model.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(12.dp))

                        // Download progress bar
                        if (isDownloading) {
                            Column {
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier.fillMaxWidth(),
                                    strokeCap = StrokeCap.Round
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${(animatedProgress * 100).toInt()}%  downloading...",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isDownloaded) Arrangement.SpaceBetween else Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isDownloaded) {
                                    IconButton(
                                        onClick = {
                                            if (modelDownloader.deleteModel(model.fileName)) {
                                                refreshDownloadedModels(models)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Model", tint = MaterialTheme.colorScheme.error)
                                    }
                                    Button(
                                        onClick = {
                                            modelDownloader.getDownloadedModelPath(model.fileName)
                                                ?.let { path -> onModelSelected(path) }
                                        }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Load Model")
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                downloadProgress[model.fileName] = 0f
                                                modelDownloader.downloadModel(model.url, model.fileName)
                                                    .collect { p ->
                                                        if (p < 0f) {
                                                            // Error sentinel
                                                            downloadProgress.remove(model.fileName)
                                                        } else {
                                                            downloadProgress[model.fileName] = p
                                                            if (p >= 1.0f) {
                                                                // Small delay for OS to flush the file
                                                                kotlinx.coroutines.delay(600)
                                                                // Rescan disk on IO thread, then refresh UI
                                                                val freshSet = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                                    models.filter {
                                                                        modelDownloader.getDownloadedModelPath(it.fileName) != null
                                                                    }.map { it.name }.toSet()
                                                                }
                                                                downloadedModels = freshSet
                                                                downloadProgress.remove(model.fileName)
                                                            }
                                                        }
                                                    }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Download")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
