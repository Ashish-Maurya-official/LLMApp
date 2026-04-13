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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.llmapp.ui.theme.LLMAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    private val modelPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleModelSelected(it) }
    }

    companion object {
        init {
            System.loadLibrary("llm_jni")
        }
    }

    // JNI Declarations
    external fun loadModel(modelPath: String): Boolean
    external fun generateResponse(prompt: String)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()

        setContent {
            LLMAppTheme {
                ChatScreen(
                    viewModel = viewModel,
                    hasPermission = hasPermission,
                    onRequestPermission = { requestStoragePermission() },
                    onSelectModel = { modelPickerLauncher.launch(arrayOf("*/*")) }
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
            initModel(path)
        } else {
            viewModel.setError("Could not resolve file path. Try choosing from a different location.")
        }
    }

    private fun initModel(path: String = "/storage/emulated/0/Download/gemma-2-2b-it-Q8_0.gguf") {
        lifecycleScope.launch(Dispatchers.IO) {
            unloadModel() // Ensure previous model is unloaded
            viewModel.updateStatus("Loading model: ${path.substringAfterLast("/")}...")
            val success = loadModel(path)
            withContext(Dispatchers.Main) {
                if (success) {
                    viewModel.updateStatus("Model loaded ✅")
                } else {
                    viewModel.setError("Failed to load model. Please ensure the file is a valid .gguf.")
                }
            }
        }
    }

    private fun handleSend(prompt: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            generateResponse(prompt)
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
        onRequestPermission: () -> Unit,
        onSelectModel: () -> Unit
    ) {
        val messages = viewModel.messages
        val status by viewModel.status
        val isGenerating by viewModel.isGenerating
        val listState = rememberLazyListState()

        // Auto-scroll to bottom when new messages arrive
        LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("LLM Chat", fontWeight = FontWeight.Bold)
                            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    actions = {
                        IconButton(onClick = onSelectModel) {
                            Icon(androidx.compose.material.icons.Icons.Default.Folder, contentDescription = "Select Model")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                )
            },
            bottomBar = {
                if (hasPermission) {
                    ChatInput(isGenerating) { text ->
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages) { message ->
                            MessageBubble(message)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun MessageBubble(message: ChatMessage) {
        val alignment = if (message.isUser) Alignment.End else Alignment.Start
        val bgColor = if (message.isUser) {
            Brush.horizontalGradient(listOf(Color(0xFF6200EE), Color(0xFF3700B3)))
        } else {
            Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant))
        }
        val textColor = if (message.isUser) Color.White else MaterialTheme.colorScheme.onSurface

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (message.isUser) 16.dp else 0.dp,
                            bottomEnd = if (message.isUser) 0.dp else 16.dp
                        )
                    )
                    .background(bgColor)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    color = if (message.isError) MaterialTheme.colorScheme.error else textColor,
                    fontSize = 15.sp
                )
            }
        }
    }

    @Composable
    fun ChatInput(isGenerating: Boolean, onSend: (String) -> Unit) {
        var text by remember { mutableStateOf("") }

        Surface(
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Ask anything...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    enabled = !isGenerating
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (text.isNotBlank() && !isGenerating) {
                            onSend(text)
                            text = ""
                        }
                    },
                    containerColor = if (isGenerating) Color.Gray else MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.size(56.dp)
                ) {
                    if (isGenerating) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = "Send")
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
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}