package com.example.llmapp.ui.models

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    onModelSelectedWithBackend: (path: String, isOrchestrator: Boolean, backend: String) -> Unit,
    onClearError: () -> Unit,
    onClearFallback: () -> Unit,
    openDrawer: () -> Unit,
    onOpenEvaluation: () -> Unit,
    settingsManager: com.example.llmapp.core.settings.SettingsManager,
    isMainModelLoaded: Boolean,
    isOrchestratorLoaded: Boolean,
    activeMainBackend: String? = null,
    activeOrchestratorBackend: String? = null,
    isLoadingModel: Boolean = false,
    loadError: String? = null,
    loadStatus: String = "Ready",
    fallbackWarning: String? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var models by remember { mutableStateOf(fallbackModels) }
    var isLoadingCatalog by remember { mutableStateOf(true) }
    var catalogSource by remember { mutableStateOf("") }

    // State to trigger recomposition when loaded models change
    var activeOrchestratorPath by remember { mutableStateOf(if (isOrchestratorLoaded) settingsManager.defaultOrchestratorModelPath else "") }
    var activeMainPath by remember { mutableStateOf(if (isMainModelLoaded) settingsManager.defaultMainModelPath else "") }

    // Sync paths when external load/unload state changes
    LaunchedEffect(isMainModelLoaded, activeMainBackend) {
        activeMainPath = if (isMainModelLoaded) settingsManager.defaultMainModelPath else ""
    }
    LaunchedEffect(isOrchestratorLoaded, activeOrchestratorBackend) {
        activeOrchestratorPath = if (isOrchestratorLoaded) settingsManager.defaultOrchestratorModelPath else ""
    }

    // Track per-model download progress (0..1), null = not downloading
    val downloadProgress = remember { mutableStateMapOf<String, Float>() }

    // Reactive set of downloaded model names
    var downloadedModels by remember { mutableStateOf(emptySet<String>()) }

    // Error dialog state
    var showErrorDialog by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }

    // Fallback warning dialog state
    var showFallbackDialog by remember { mutableStateOf(false) }
    var lastFallback by remember { mutableStateOf<String?>(null) }

    // Show error dialog when a new error arrives
    LaunchedEffect(loadError) {
        if (!loadError.isNullOrBlank()) {
            lastError = loadError
            showErrorDialog = true
        }
    }

    // Show fallback dialog when a fallback occurs
    LaunchedEffect(fallbackWarning) {
        if (!fallbackWarning.isNullOrBlank()) {
            lastFallback = fallbackWarning
            showFallbackDialog = true
        }
    }

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

    // ── Error Dialog ────────────────────────────────────────────────────────
    if (showErrorDialog && lastError != null) {
        AlertDialog(
            onDismissRequest = {
                showErrorDialog = false
                onClearError()
            },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    "Model Loading Failed",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    lastError ?: "Unknown error occurred",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showErrorDialog = false
                    onClearError()
                }) {
                    Text("OK")
                }
            }
        )
    }

    // ── Fallback Warning Dialog ─────────────────────────────────────────────
    if (showFallbackDialog && lastFallback != null) {
        AlertDialog(
            onDismissRequest = {
                showFallbackDialog = false
                onClearFallback()
            },
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "Fallback",
                    tint = MaterialTheme.colorScheme.tertiary
                )
            },
            title = {
                Text(
                    "Backend Fallback",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    lastFallback ?: "",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFallbackDialog = false
                    onClearFallback()
                }) {
                    Text("OK")
                }
            }
        )
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
                val modelPath = modelDownloader.getDownloadedModelPath(model.fileName)
                val isThisLoaded = modelPath != null && modelPath == activeOrchestratorPath

                ModelCard(
                    model = model,
                    isDownloaded = downloadedModels.contains(model.name),
                    progress = downloadProgress[model.fileName],
                    isModelLoading = isLoadingModel,
                    loadStatus = loadStatus,
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
                            if (modelPath != null && modelPath == settingsManager.defaultOrchestratorModelPath) {
                                settingsManager.defaultOrchestratorModelPath = ""
                                activeOrchestratorPath = ""
                                onModelUnloaded(modelPath, true)
                            }
                        }
                    },
                    onLoadWithBackend = { backend ->
                        modelPath?.let { path ->
                            // If already loaded, unload first (backend switch)
                            if (isThisLoaded) {
                                onModelUnloaded(path, true)
                            }
                            settingsManager.defaultOrchestratorModelPath = path
                            settingsManager.orchestratorHardwareBackend = backend
                            activeOrchestratorPath = path
                            onModelSelectedWithBackend(path, true, backend)
                        }
                    },
                    onUnload = {
                        modelPath?.let { path ->
                            activeOrchestratorPath = ""
                            onModelUnloaded(path, true)
                        }
                    },
                    isLoaded = isThisLoaded,
                    activeBackend = if (isThisLoaded) activeOrchestratorBackend else null,
                    defaultBackend = settingsManager.orchestratorHardwareBackend
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
                val modelPath = modelDownloader.getDownloadedModelPath(model.fileName)
                val isThisLoaded = modelPath != null && modelPath == activeMainPath

                ModelCard(
                    model = model,
                    isDownloaded = downloadedModels.contains(model.name),
                    progress = downloadProgress[model.fileName],
                    isModelLoading = isLoadingModel,
                    loadStatus = loadStatus,
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
                            if (modelPath != null && modelPath == settingsManager.defaultMainModelPath) {
                                settingsManager.defaultMainModelPath = ""
                                activeMainPath = ""
                                onModelUnloaded(modelPath, false)
                            }
                        }
                    },
                    onLoadWithBackend = { backend ->
                        modelPath?.let { path ->
                            // If already loaded, unload first (backend switch)
                            if (isThisLoaded) {
                                onModelUnloaded(path, false)
                            }
                            settingsManager.defaultMainModelPath = path
                            settingsManager.mainHardwareBackend = backend
                            activeMainPath = path
                            onModelSelectedWithBackend(path, false, backend)
                        }
                    },
                    onUnload = {
                        modelPath?.let { path ->
                            activeMainPath = ""
                            onModelUnloaded(path, false)
                        }
                    },
                    isLoaded = isThisLoaded,
                    activeBackend = if (isThisLoaded) activeMainBackend else null,
                    defaultBackend = settingsManager.mainHardwareBackend
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
