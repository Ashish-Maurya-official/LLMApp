package com.example.llmapp.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.llmapp.core.voice.VoiceManager
import com.example.llmapp.ui.state.ChatIntent
import com.example.llmapp.ui.state.ChatUiState
import com.example.llmapp.ui.state.VoiceState
import com.example.llmapp.ui.voice.VoiceConversationOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    openDrawer: () -> Unit,
    onRegisterTokenCallback: ((token: String, done: Boolean) -> Unit) -> Unit = {},
    settingsManager: com.example.llmapp.core.settings.SettingsManager? = null
) {
    var inputText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }

    val voiceManager = remember {
        VoiceManager(
            context = context,
            onSpeechResult = { text ->
                onIntent(ChatIntent.SetPartialTranscript(text))
                onIntent(ChatIntent.SetVoiceState(VoiceState.THINKING))
                onIntent(ChatIntent.SendMessage(text))
            },
            onListeningStateChanged = { listening ->
                isListening = listening
                if (listening) onIntent(ChatIntent.SetVoiceState(VoiceState.LISTENING))
            },
            onPartialResult = { partial ->
                onIntent(ChatIntent.SetPartialTranscript(partial))
            },
            onSpeakingStateChanged = { speaking ->
                if (speaking) onIntent(ChatIntent.SetVoiceState(VoiceState.SPEAKING))
                else if (uiState.isVoiceModeActive) onIntent(ChatIntent.SetVoiceState(VoiceState.LISTENING))
            },
            onError = { error ->
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            },
            speechRate = settingsManager?.ttsSpeechRate ?: 0.95f,
            voiceName = settingsManager?.ttsVoiceName ?: ""
        )
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            voiceManager.isVoiceModeActive = true
            onIntent(ChatIntent.ActivateVoiceMode)
            voiceManager.startListening()
        } else {
            Toast.makeText(context, "Microphone permission required for voice mode", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        // Register real token callback with ViewModel for true streaming TTS
        onRegisterTokenCallback { token, done ->
            if (voiceManager.isVoiceModeActive) {
                voiceManager.feedToken(token, done)
            }
        }
        onDispose {
            voiceManager.destroy()
        }
    }

    // Fallback: simple one-shot speak when NOT in voice mode
    val isGenerating = uiState.isGenerating
    val messages = uiState.messages
    var prevGenerating by remember { mutableStateOf(false) }
    LaunchedEffect(isGenerating) {
        if (prevGenerating && !isGenerating && !uiState.isVoiceModeActive) {
            val last = messages.lastOrNull()
            if (last != null && !last.isUser && last.text.isNotBlank()) {
                voiceManager.speak(last.text)
            }
        }
        prevGenerating = isGenerating
    }

    fun startVoiceMode() {
        voiceManager.resetForNewGeneration()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceManager.isVoiceModeActive = true
            onIntent(ChatIntent.ActivateVoiceMode)
            voiceManager.startListening()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    // New Chat button
                    IconButton(onClick = { onIntent(ChatIntent.ClearHistory) }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "New Chat",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Live Voice Mode button
                    IconButton(onClick = { startVoiceMode() }) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Live Voice Mode",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (uiState.errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = uiState.errorMessage,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Empty state
            if (uiState.messages.isEmpty() && !uiState.isGenerating) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(80.dp)
                        ) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                modifier = Modifier.padding(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "Ready to chat",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (uiState.status == "Ready")
                                "Type a message or tap the mic to start a conversation."
                            else
                                "Open the drawer → Models to download and load a model first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            items(uiState.messages) { msg ->
                if (msg.isUser) {
                    UserMessageBubble(text = msg.text)
                } else {
                    AssistantMessageBubble(
                        text = msg.text,
                        onCopy = { clipboardManager.setText(AnnotatedString(msg.text)) }
                    )
                }
            }
            if (uiState.isGenerating) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Thinking...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Input Area
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .animateContentSize(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 4.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(onClick = { }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add Attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = 2.dp),
                    placeholder = {
                        Text(
                            "Message AI...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    minLines = 1,
                    maxLines = 8,
                    enabled = !uiState.isGenerating
                )

                Spacer(modifier = Modifier.width(8.dp))

                val sendBtnColor by animateColorAsState(
                    targetValue = if (inputText.isNotBlank() && !uiState.isGenerating) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                )
                val sendIconColor by animateColorAsState(
                    targetValue = if (inputText.isNotBlank() && !uiState.isGenerating) 
                        MaterialTheme.colorScheme.onPrimary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isListening) {
                    IconButton(
                        onClick = { voiceManager.stopListening() },
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Stop Listening",
                            tint = MaterialTheme.colorScheme.onError
                        )
                    }
                } else if (inputText.isNotBlank() && !uiState.isGenerating) {
                    IconButton(
                        onClick = {
                            onIntent(ChatIntent.SendMessage(inputText))
                            inputText = ""
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .background(sendBtnColor, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = sendIconColor
                        )
                    }
                } else {
                    // Tap mic icon in input field → simple one-shot voice input (not live mode)
                    IconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                voiceManager.startListening()
                            } else {
                                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Voice Mode Full-Screen Overlay
        if (uiState.isVoiceModeActive) {
            VoiceConversationOverlay(
                voiceState = uiState.voiceState,
                partialTranscript = uiState.partialTranscript,
                onInterrupt = {
                    voiceManager.interrupt()
                    onIntent(ChatIntent.SetVoiceState(VoiceState.LISTENING))
                },
                onDismiss = {
                    voiceManager.interrupt()
                    voiceManager.isVoiceModeActive = false
                    voiceManager.stopListening()
                    onIntent(ChatIntent.DeactivateVoiceMode)
                }
            )
        }

        // Loading Overlay
        if (uiState.isLoadingModel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Loading Model to Memory...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This may take a few seconds on first load.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
}
}

@Composable
fun UserMessageBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun AssistantMessageBubble(text: String, onCopy: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(Icons.Default.SmartToy, contentDescription = "AI", modifier = Modifier.padding(6.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ParsedMarkdownMessage(text = text)
            
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ContentCopy, 
                        contentDescription = "Copy Response", 
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

sealed class MessagePart {
    data class PlainMarkdown(val content: String) : MessagePart()
    data class Code(val language: String, val content: String) : MessagePart()
}

fun parseMarkdownParts(text: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    val codeBlockRegex = Regex("```(\\w*)[\\r\\n]+([\\s\\S]*?)```")
    var lastIndex = 0
    codeBlockRegex.findAll(text).forEach { match ->
        val textBefore = text.substring(lastIndex, match.range.first)
        if (textBefore.isNotBlank()) parts.add(MessagePart.PlainMarkdown(textBefore))
        parts.add(MessagePart.Code(match.groupValues[1], match.groupValues[2]))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex)
        if (remaining.isNotBlank()) parts.add(MessagePart.PlainMarkdown(remaining))
    }
    return parts.ifEmpty { listOf(MessagePart.PlainMarkdown(text)) }
}

@Composable
fun MarkwonText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) {
        io.noties.markwon.Markwon.builder(context)
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
            .build()
    }
    val surfaceColor = MaterialTheme.colorScheme.onSurface
    val bodyLargeSize = MaterialTheme.typography.bodyLarge.fontSize.value

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                setTextColor(surfaceColor.hashCode())
                textSize = bodyLargeSize
                setLineSpacing(4f, 1.1f)
            }
        },
        update = { view ->
            markwon.setMarkdown(view, markdown)
        },
        modifier = modifier
    )
}

@Composable
fun ParsedMarkdownMessage(text: String) {
    val parts = parseMarkdownParts(text)
    val clipboardManager = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is MessagePart.PlainMarkdown -> {
                    MarkwonText(
                        markdown = part.content.trim(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is MessagePart.Code -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E1E1E),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF2D2D2D))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (part.language.isBlank()) "code" else part.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                                IconButton(
                                    onClick = { clipboardManager.setText(AnnotatedString(part.content)) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy Code",
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = part.content.trimEnd(),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFFD4D4D4),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
