package com.example.llmapp.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.models.ModelDownloader
import com.example.llmapp.ui.models.composables.ModelCard
import com.example.llmapp.ui.models.composables.ModelHeader
import com.example.llmapp.ui.models.state.AvailableModel
import com.example.llmapp.ui.models.state.fallbackModels
import com.example.llmapp.ui.models.state.fetchRemoteModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(
    modelDownloader: ModelDownloader,
    onModelSelected: (String, Boolean) -> Unit,
    onModelUnloaded: (String, Boolean) -> Unit,
    openDrawer: () -> Unit,
    onOpenEvaluation: () -> Unit,
    settingsManager: com.example.llmapp.core.settings.SettingsManager,
    isMainModelLoaded: Boolean,
    isOrchestratorLoaded: Boolean,
    activeMainBackend: String? = null,
    activeOrchestratorBackend: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var models by remember { mutableStateOf(fallbackModels) }
    var isLoadingCatalog by remember { mutableStateOf(true) }
    var catalogSource by remember { mutableStateOf("") }

    // State to trigger recomposition when loaded models change
    var activeOrchestratorPath by remember { mutableStateOf(if (isOrchestratorLoaded) settingsManager.defaultOrchestratorModelPath else "") }
    var activeMainPath by remember { mutableStateOf(if (isMainModelLoaded) settingsManager.defaultMainModelPath else "") }

    // Track per-model download progress (0..1), null = not downloading
    val downloadProgress = remember { mutableStateMapOf<String, Float>() }

    // Reactive set of downloaded model names
    var downloadedModels by remember { mutableStateOf(emptySet<String>()) }

    fun refreshDownloadedModels(currentModels: List<AvailableModel>) {
        downloadedModels = currentModels
            .filter { modelDownloader.getDownloadedModelPath(it.fileName) != null }
            .map { it.name }
            .toSet()
    }

    LaunchedEffect(Unit) {
        isLoadingCatalog = true
        downloadedModels = withContext(Dispatchers.IO) {
            fallbackModels.filter { modelDownloader.getDownloadedModelPath(it.fileName) != null }
                .map { it.name }.toSet()
        }

        val remote: List<AvailableModel>? = null // withContext(Dispatchers.IO) { fetchRemoteModels() }
        val combined = if (remote != null) {
            val merged = (fallbackModels + remote).distinctBy { it.fileName }
            catalogSource = "Remote catalog synced"
            merged
        } else {
            catalogSource = "Offline — using local catalog"
            fallbackModels
        }
        models = combined

        downloadedModels = withContext(Dispatchers.IO) {
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
                ModelHeader(
                    isLoadingCatalog = isLoadingCatalog,
                    catalogSource = catalogSource,
                    onOpenEvaluation = onOpenEvaluation
                )
            }

            val orchestratorModels = models.filter { it.tags.contains("Orchestrator") }
            val mainModels = models.filter { !it.tags.contains("Orchestrator") }

            item {
                Text(
                    text = "Cognitive Orchestrators (Level 1)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(orchestratorModels, key = { it.fileName }) { model ->
                ModelCard(
                    model = model,
                    isDownloaded = downloadedModels.contains(model.name),
                    progress = downloadProgress[model.fileName],
                    onDownload = {
                        coroutineScope.launch {
                            downloadProgress[model.fileName] = 0f
                            modelDownloader.downloadModel(model.url, model.fileName)
                                .collect { p ->
                                    if (p < 0f) {
                                        downloadProgress.remove(model.fileName)
                                    } else {
                                        downloadProgress[model.fileName] = p
                                        if (p >= 1.0f) {
                                            kotlinx.coroutines.delay(600)
                                            val freshSet = withContext(Dispatchers.IO) {
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
                    },
                    onDelete = {
                        if (modelDownloader.deleteModel(model.fileName)) {
                            refreshDownloadedModels(models)
                            val path = modelDownloader.getDownloadedModelPath(model.fileName)
                            if (path != null && path == settingsManager.defaultOrchestratorModelPath) {
                                settingsManager.defaultOrchestratorModelPath = ""
                                activeOrchestratorPath = ""
                                onModelUnloaded(path, true)
                            }
                        }
                    },
                    onLoad = {
                        modelDownloader.getDownloadedModelPath(model.fileName)?.let { path ->
                            if (path == activeOrchestratorPath) {
                                // Already loaded, so unload it
                                activeOrchestratorPath = ""
                                onModelUnloaded(path, true)
                            } else {
                                // Load it
                                settingsManager.defaultOrchestratorModelPath = path
                                activeOrchestratorPath = path
                                onModelSelected(path, true)
                            }
                        }
                    },
                    isLoaded = modelDownloader.getDownloadedModelPath(model.fileName) == activeOrchestratorPath,
                    activeBackend = if (modelDownloader.getDownloadedModelPath(model.fileName) == activeOrchestratorPath) activeOrchestratorBackend else null
                )
            }

            item {
                Text(
                    text = "Main Reasoning Models (Level 2)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(mainModels, key = { it.fileName }) { model ->
                ModelCard(
                    model = model,
                    isDownloaded = downloadedModels.contains(model.name),
                    progress = downloadProgress[model.fileName],
                    onDownload = {
                        coroutineScope.launch {
                            downloadProgress[model.fileName] = 0f
                            modelDownloader.downloadModel(model.url, model.fileName)
                                .collect { p ->
                                    if (p < 0f) {
                                        downloadProgress.remove(model.fileName)
                                    } else {
                                        downloadProgress[model.fileName] = p
                                        if (p >= 1.0f) {
                                            kotlinx.coroutines.delay(600)
                                            val freshSet = withContext(Dispatchers.IO) {
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
                    },
                    onDelete = {
                        if (modelDownloader.deleteModel(model.fileName)) {
                            refreshDownloadedModels(models)
                            val path = modelDownloader.getDownloadedModelPath(model.fileName)
                            if (path != null && path == settingsManager.defaultMainModelPath) {
                                settingsManager.defaultMainModelPath = ""
                                activeMainPath = ""
                                onModelUnloaded(path, false)
                            }
                        }
                    },
                    onLoad = {
                        modelDownloader.getDownloadedModelPath(model.fileName)?.let { path ->
                            if (path == activeMainPath) {
                                // Already loaded, so unload it
                                activeMainPath = ""
                                onModelUnloaded(path, false)
                            } else {
                                // Load it
                                settingsManager.defaultMainModelPath = path
                                activeMainPath = path
                                onModelSelected(path, false)
                            }
                        }
                    },
                    isLoaded = modelDownloader.getDownloadedModelPath(model.fileName) == activeMainPath,
                    activeBackend = if (modelDownloader.getDownloadedModelPath(model.fileName) == activeMainPath) activeMainBackend else null
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
