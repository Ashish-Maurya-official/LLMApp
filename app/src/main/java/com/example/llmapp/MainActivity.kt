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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
                    onSelectModel = { modelPickerLauncher.launch(arrayOf("*/*")) },
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
        onSelectModel: () -> Unit,
        onNewChat: () -> Unit
    ) {
        val messages = viewModel.messages
        val status by viewModel.status
        val isGenerating by viewModel.isGenerating
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("LLM Assistant", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
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
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
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
    fun ChatInput(isGenerating: Boolean, onSend: (String) -> Unit) {
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
                    placeholder = { Text("Message...") },
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