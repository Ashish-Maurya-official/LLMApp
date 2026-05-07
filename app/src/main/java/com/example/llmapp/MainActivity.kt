package com.example.llmapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.llmapp.ui.theme.AiGreen
import com.example.llmapp.ui.theme.LLMAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private var currentModelPath = "/storage/emulated/0/Download/gemma-2-2b-it-Q8_0.gguf"

    private val modelPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleModelSelected(it) }
    }

    private val contextPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleContextSelected(it) }
    }

    companion object {
        init {
            System.loadLibrary("llm_jni")
        }
    }

    // JNI Declarations
    external fun loadModel(modelPath: String, nGpuLayers: Int, contextSize: Int): Boolean
    external fun generateResponse(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float
    )
    external fun getBackendInfo(): String
    external fun unloadModel()

    // Status tracking for permissions
    private var hasPermission by mutableStateOf(false)

    // Callbacks from JNI
    fun onStatusUpdate(status: String) {
        runOnUiThread { viewModel.updateStatus(status) }
    }

    fun onTokenGenerated(token: String) {
        runOnUiThread { viewModel.onTokenGenerated(token) }
    }

    fun onComplete() {
        runOnUiThread { viewModel.onGenerationComplete() }
    }

    fun onGenerationStats(stats: String) {
        runOnUiThread { viewModel.updateGenerationStats(stats) }
    }

    fun onError(error: String) {
        runOnUiThread { viewModel.setError(error) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()

        setContent {
            LLMAppTheme {
                ChatScreen(
                    viewModel = viewModel,
                    hasPermission = hasPermission,
                    modelPath = currentModelPath,
                    onRequestPermission = { requestStoragePermission() },
                    onSelectModel = { modelPickerLauncher.launch(arrayOf("*/*")) },
                    onImportContext = {
                        contextPickerLauncher.launch(
                            arrayOf("text/*", "application/json", "application/xml")
                        )
                    },
                    onReloadModel = { initModel(currentModelPath) },
                    onNewChat = { viewModel.clearMessages() }
                )
            }
        }
    }

    private fun checkPermissions() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        hasPermission = granted
        if (granted) {
            initModel()
        }
    }

    private fun handleModelSelected(uri: Uri) {
        val path = FileUtils.getPath(this, uri)
        if (path != null) {
            currentModelPath = path
            initModel(path)
        } else {
            viewModel.setError("Could not resolve file path. Try choosing from a different location.")
        }
    }

    private fun handleContextSelected(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                    .take(12_000)
                withContext(Dispatchers.Main) {
                    if (text.isBlank()) {
                        viewModel.setError("Could not read text from the selected file.")
                    } else {
                        viewModel.updateWorkspaceContext(text)
                        viewModel.updateStatus("Imported ${text.length} chars into workspace context")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.setError("Failed to import context: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun initModel(path: String = currentModelPath) {
        currentModelPath = path
        lifecycleScope.launch(Dispatchers.IO) {
            unloadModel() // Ensure previous model is unloaded
            viewModel.updateStatus("Loading model: ${path.substringAfterLast("/")}...")
            val success = loadModel(
                path,
                viewModel.gpuLayers.value,
                viewModel.contextSize.value
            )
            withContext(Dispatchers.Main) {
                if (success) {
                    viewModel.updateStatus("Model loaded | ${getBackendInfo()}")
                } else {
                    viewModel.setError("Failed to load model. Please ensure the file is a valid .gguf.")
                }
            }
        }
    }

    private fun handleSend(prompt: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            generateResponse(
                prompt,
                viewModel.maxOutputTokens.value,
                viewModel.temperature.value,
                viewModel.topK.value,
                viewModel.topP.value
            )
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ChatScreen(
        viewModel: ChatViewModel,
        hasPermission: Boolean,
        modelPath: String,
        onRequestPermission: () -> Unit,
        onSelectModel: () -> Unit,
        onImportContext: () -> Unit,
        onReloadModel: () -> Unit,
        onNewChat: () -> Unit
    ) {
        val messages = viewModel.messages
        val status by viewModel.status
        val isGenerating by viewModel.isGenerating
        val selectedMode by viewModel.selectedMode
        val listState = rememberLazyListState()
        var showSettings by remember { mutableStateOf(false) }

        LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        if (showSettings) {
            SettingsScreen(
                viewModel = viewModel,
                isGenerating = isGenerating,
                modelPath = modelPath,
                onBack = { showSettings = false },
                onSelectModel = onSelectModel,
                onImportContext = onImportContext,
                onReloadModel = onReloadModel,
                onNewChat = onNewChat
            )
            return
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Offline AI", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                            Text(status, style = MaterialTheme.typography.labelSmall, color = AiGreen)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Default.Add, contentDescription = "New Chat")
                        }
                    },
                    actions = {
                        IconButton(onClick = onSelectModel) {
                            Icon(Icons.Default.Folder, contentDescription = "Select Model")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (hasPermission) {
                    ChatInput(
                        isGenerating = isGenerating,
                        placeholder = selectedMode.inputHint
                    ) { text ->
                        viewModel.sendMessage(text) { handleSend(it) }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (!hasPermission) {
                    PermissionRequestView(onRequestPermission)
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        AiModeBar(
                            viewModel = viewModel,
                            isGenerating = isGenerating,
                            onImportContext = onImportContext
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            items(messages) { message ->
                                MessageRow(message)
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(
        viewModel: ChatViewModel,
        isGenerating: Boolean,
        modelPath: String,
        onBack: () -> Unit,
        onSelectModel: () -> Unit,
        onImportContext: () -> Unit,
        onReloadModel: () -> Unit,
        onNewChat: () -> Unit
    ) {
        val status by viewModel.status
        val lastStats by viewModel.lastStats
        val selectedMode by viewModel.selectedMode
        val workspaceContext by viewModel.workspaceContext
        val customSystemInstruction by viewModel.customSystemInstruction
        val includeRecentMessages by viewModel.includeRecentMessages
        val recentMessageLimit by viewModel.recentMessageLimit
        val includeWorkspaceContext by viewModel.includeWorkspaceContext
        val workspaceContextLimit by viewModel.workspaceContextLimit
        val gpuLayers by viewModel.gpuLayers
        val contextSize by viewModel.contextSize
        val maxOutputTokens by viewModel.maxOutputTokens
        val temperature by viewModel.temperature
        val topK by viewModel.topK
        val topP by viewModel.topP

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    SettingsSection(title = "Model") {
                        Text("Current model", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = modelPath.substringAfterLast("/"),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onSelectModel,
                                enabled = !isGenerating,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Select")
                            }
                            Button(
                                onClick = onReloadModel,
                                enabled = !isGenerating,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reload")
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(status, style = MaterialTheme.typography.labelSmall, color = AiGreen)
                        if (lastStats.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(lastStats, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                item {
                    SettingsSection(title = "AI Modes") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AiMode.values().forEach { mode ->
                                FilterChip(
                                    selected = mode == selectedMode,
                                    onClick = { viewModel.updateMode(mode) },
                                    enabled = !isGenerating,
                                    label = { Text(mode.label) },
                                    leadingIcon = if (mode == selectedMode) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsSection(title = "Behavior") {
                        OutlinedTextField(
                            value = customSystemInstruction,
                            onValueChange = { viewModel.updateCustomSystemInstruction(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp, max = 180.dp),
                            enabled = !isGenerating,
                            label = { Text("Custom system instruction") },
                            placeholder = { Text("Example: Answer in short bullets. Prefer Hindi. Be strict about math.") },
                            shape = RoundedCornerShape(8.dp),
                            maxLines = 8
                        )
                        SettingsSwitch(
                            title = "Use recent chat history",
                            subtitle = "Include previous messages when building the offline prompt.",
                            checked = includeRecentMessages,
                            enabled = !isGenerating,
                            onCheckedChange = { viewModel.updateIncludeRecentMessages(it) }
                        )
                        SettingSlider(
                            label = "Recent messages: $recentMessageLimit",
                            value = recentMessageLimit.toFloat(),
                            range = 0f..30f,
                            enabled = !isGenerating && includeRecentMessages,
                            onValueChange = { viewModel.updateRecentMessageLimit(it.toInt()) }
                        )
                    }
                }

                item {
                    SettingsSection(title = "Workspace Context") {
                        SettingsSwitch(
                            title = "Attach workspace context",
                            subtitle = "Use imported or pasted context in every AI mode.",
                            checked = includeWorkspaceContext,
                            enabled = !isGenerating,
                            onCheckedChange = { viewModel.updateIncludeWorkspaceContext(it) }
                        )
                        SettingSlider(
                            label = "Context limit: $workspaceContextLimit chars",
                            value = workspaceContextLimit.toFloat(),
                            range = 2000f..24000f,
                            enabled = !isGenerating,
                            onValueChange = { viewModel.updateWorkspaceContextLimit(((it / 1000).toInt() * 1000).coerceAtLeast(2000)) }
                        )
                        Text(
                            text = "Current workspace: ${workspaceContext.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = workspaceContext,
                            onValueChange = { viewModel.updateWorkspaceContext(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 220.dp),
                            enabled = !isGenerating,
                            placeholder = { Text("Paste notes, documents, code, or instructions here.") },
                            shape = RoundedCornerShape(8.dp),
                            maxLines = 10
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onImportContext,
                                enabled = !isGenerating,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import")
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearWorkspaceContext() },
                                enabled = !isGenerating && workspaceContext.isNotBlank(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear")
                            }
                        }
                    }
                }

                item {
                    SettingsSection(title = "Runtime") {
                        SettingSlider(
                            label = if (gpuLayers < 0) "GPU layers: auto" else "GPU layers: $gpuLayers",
                            value = if (gpuLayers < 0) 80f else gpuLayers.toFloat(),
                            range = 0f..80f,
                            enabled = !isGenerating,
                            onValueChange = { value ->
                                viewModel.updateGpuLayers(if (value >= 79f) -1 else value.toInt())
                            }
                        )
                        SettingSlider(
                            label = "Context window: $contextSize tokens",
                            value = contextSize.toFloat(),
                            range = 512f..4096f,
                            enabled = !isGenerating,
                            onValueChange = { viewModel.updateContextSize(((it / 512).toInt() * 512).coerceAtLeast(512)) }
                        )
                        Text(
                            text = "Reload the model after changing runtime settings.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                    }
                }

                item {
                    SettingsSection(title = "Generation") {
                        SettingSlider(
                            label = "Max output: $maxOutputTokens tokens",
                            value = maxOutputTokens.toFloat(),
                            range = 64f..2048f,
                            enabled = !isGenerating,
                            onValueChange = { viewModel.updateMaxOutputTokens(((it / 64).toInt() * 64).coerceAtLeast(64)) }
                        )
                        SettingSlider(
                            label = "Temperature: ${"%.2f".format(temperature)}",
                            value = temperature,
                            range = 0f..1.5f,
                            enabled = !isGenerating,
                            onValueChange = { viewModel.updateTemperature(it) }
                        )
                        SettingSlider(
                            label = "Top-k: $topK",
                            value = topK.toFloat(),
                            range = 1f..100f,
                            enabled = !isGenerating,
                            onValueChange = { viewModel.updateTopK(it.toInt().coerceAtLeast(1)) }
                        )
                        SettingSlider(
                            label = "Top-p: ${"%.2f".format(topP)}",
                            value = topP,
                            range = 0.05f..1f,
                            enabled = !isGenerating,
                            onValueChange = { viewModel.updateTopP(it.coerceIn(0.05f, 1f)) }
                        )
                    }
                }

                item {
                    SettingsSection(title = "Conversation") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onNewChat,
                                enabled = !isGenerating,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New chat")
                            }
                            OutlinedButton(
                                onClick = { viewModel.resetSettings() },
                                enabled = !isGenerating,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset settings")
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
    }

    @Composable
    fun SettingsSwitch(
        title: String,
        subtitle: String,
        checked: Boolean,
        enabled: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }

    @Composable
    fun AiModeBar(
        viewModel: ChatViewModel,
        isGenerating: Boolean,
        onImportContext: () -> Unit
    ) {
        val selectedMode by viewModel.selectedMode
        val workspaceContext by viewModel.workspaceContext
        var showContext by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AiMode.values().forEach { mode ->
                    FilterChip(
                        selected = mode == selectedMode,
                        onClick = { viewModel.updateMode(mode) },
                        enabled = !isGenerating,
                        label = { Text(mode.label) },
                        leadingIcon = if (mode == selectedMode) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (workspaceContext.isBlank()) "Workspace context: empty" else "Workspace context: ${workspaceContext.length} chars",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
                TextButton(onClick = { showContext = !showContext }, enabled = !isGenerating) {
                    Text(if (showContext) "Hide" else "Edit")
                }
            }

            if (showContext) {
                OutlinedTextField(
                    value = workspaceContext,
                    onValueChange = { viewModel.updateWorkspaceContext(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 180.dp),
                    enabled = !isGenerating,
                    placeholder = { Text("Paste notes, documents, code, or instructions here. The model will use this for every mode.") },
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 8
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onImportContext,
                        enabled = !isGenerating
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import")
                    }
                    TextButton(
                        onClick = { viewModel.clearWorkspaceContext() },
                        enabled = !isGenerating && workspaceContext.isNotBlank()
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
    }

    @Composable
    fun ModelControls(
        viewModel: ChatViewModel,
        isGenerating: Boolean,
        onReloadModel: () -> Unit
    ) {
        val gpuLayers by viewModel.gpuLayers
        val contextSize by viewModel.contextSize
        val maxOutputTokens by viewModel.maxOutputTokens
        val temperature by viewModel.temperature
        val topK by viewModel.topK
        val topP by viewModel.topP

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Runtime", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedButton(
                    onClick = onReloadModel,
                    enabled = !isGenerating,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reload")
                }
            }

            SettingSlider(
                label = if (gpuLayers < 0) "GPU layers: auto" else "GPU layers: $gpuLayers",
                value = if (gpuLayers < 0) 33f else gpuLayers.toFloat(),
                range = 0f..80f,
                enabled = !isGenerating,
                onValueChange = { value ->
                    viewModel.updateGpuLayers(if (value >= 79f) -1 else value.toInt())
                }
            )
            SettingSlider(
                label = "Context: $contextSize",
                value = contextSize.toFloat(),
                range = 512f..4096f,
                enabled = !isGenerating,
                onValueChange = { viewModel.updateContextSize(((it / 512).toInt() * 512).coerceAtLeast(512)) }
            )
            SettingSlider(
                label = "Max output: $maxOutputTokens",
                value = maxOutputTokens.toFloat(),
                range = 64f..1024f,
                enabled = !isGenerating,
                onValueChange = { viewModel.updateMaxOutputTokens(((it / 64).toInt() * 64).coerceAtLeast(64)) }
            )
            SettingSlider(
                label = "Temperature: ${"%.1f".format(temperature)}",
                value = temperature,
                range = 0f..1.5f,
                enabled = !isGenerating,
                onValueChange = { viewModel.updateTemperature(it) }
            )
            SettingSlider(
                label = "Top-k: $topK",
                value = topK.toFloat(),
                range = 1f..100f,
                enabled = !isGenerating,
                onValueChange = { viewModel.updateTopK(it.toInt().coerceAtLeast(1)) }
            )
            SettingSlider(
                label = "Top-p: ${"%.2f".format(topP)}",
                value = topP,
                range = 0.05f..1f,
                enabled = !isGenerating,
                onValueChange = { viewModel.updateTopP(it.coerceIn(0.05f, 1f)) }
            )
        }
    }

    @Composable
    fun SettingSlider(
        label: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        enabled: Boolean,
        onValueChange: (Float) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                enabled = enabled,
                modifier = Modifier.height(28.dp)
            )
        }
    }

    @Composable
    fun MessageRow(message: ChatMessage) {
        val bgColor = if (message.isUser) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(vertical = 24.dp, horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (message.isUser) Color(0xFF5436DA) else AiGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (message.isUser) Icons.Default.Person else Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (message.isUser) "You" else "Assistant",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.text,
                        color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                }
            }
        }
    }

    @Composable
    fun ChatInput(
        isGenerating: Boolean,
        placeholder: String,
        onSend: (String) -> Unit
    ) {
        var text by remember { mutableStateOf("") }

        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 5,
                    enabled = !isGenerating,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AiGreen.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                )
                Spacer(modifier = Modifier.width(12.dp))
                
                IconButton(
                    onClick = {
                        if (text.isNotBlank() && !isGenerating) {
                            onSend(text)
                            text = ""
                        }
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (text.isNotBlank() && !isGenerating) AiGreen else Color.Transparent)
                        .size(40.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (text.isNotBlank()) Color.White else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionRequestView(onRequestPermission: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Storage Permission Required", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text("The app needs access to load the LLM model file.")
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission, shape = RoundedCornerShape(8.dp)) {
                Text("Grant Permission")
            }
        }
    }
}
