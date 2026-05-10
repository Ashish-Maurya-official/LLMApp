package com.example.llmapp.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import android.view.HapticFeedbackConstants
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.util.fastForEach
import com.example.llmapp.core.voice.VoiceManager
import com.example.llmapp.ui.state.ChatIntent
import com.example.llmapp.ui.state.ChatUiState
import com.example.llmapp.ui.state.VoiceState
import com.example.llmapp.ui.voice.VoiceConversationOverlay
import com.example.llmapp.ChatMessage
import com.example.llmapp.AgentAction
import com.example.llmapp.ui.state.StreamingSegment
import com.example.llmapp.ui.state.StreamingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    streamingState: State<StreamingState>,
    onIntent: (ChatIntent) -> Unit,
    openDrawer: () -> Unit,
    onRegisterTokenCallback: ((token: String, done: Boolean) -> Unit) -> Unit = {},
    settingsManager: com.example.llmapp.core.settings.SettingsManager? = null
) {
    var inputText by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && isInputFocused) {
            focusManager.clearFocus()
        }
    }

    var autoScrollEnabled by remember { mutableStateOf(true) }

    var isUserScrolling by remember { mutableStateOf(false) }

    // 1. Instantly pause auto-scroll the moment the user touches and drags the list
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) {
            isUserScrolling = true
            autoScrollEnabled = false
        }
    }

    // 2. Observer: Checks the scroll position whenever a MANUAL scroll finishes.
    // By guarding with `isUserScrolling`, we completely ignore programmatic scrollToItem
    // calls, preventing them from accidentally breaking the state.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && isUserScrolling) {
            autoScrollEnabled = listState.isNearBottom(100)
            isUserScrolling = false
        }
    }

    // Structural scroll: fires when a message is added or generation starts/ends.
    LaunchedEffect(uiState.messages.size, uiState.isGenerating) {
        if (autoScrollEnabled) {
            val anchorIndex = listState.layoutInfo.totalItemsCount - 1
            if (anchorIndex >= 0) listState.animateScrollToItem(anchorIndex)
        }
    }

    // Streaming scroll: collectLatest ensures we only handle the latest token update.
    // We wait for TWO frames to ensure markdown tables have stabilized their layout
    // (which often involves multiple measure/layout passes) before scrolling.
    val streamingContentLength by remember { derivedStateOf { streamingState.value.rawContent.length } }
    val isCurrentlyGenerating by rememberUpdatedState(uiState.isGenerating)

    LaunchedEffect(Unit) {
        snapshotFlow { streamingContentLength }
            .collectLatest {
                if (!isCurrentlyGenerating) return@collectLatest
                
                withFrameNanos { }
                withFrameNanos { }
                
                if (autoScrollEnabled) {
                    val anchorIndex = listState.layoutInfo.totalItemsCount - 1
                    if (anchorIndex >= 0) listState.scrollToItem(anchorIndex)
                }
            }
    }

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
            onInterrupted = {
                onIntent(ChatIntent.StopGeneration)
                // Clear partial transcript
                onIntent(ChatIntent.SetPartialTranscript(""))
            },
            onSpeakingStateChanged = { speaking ->
                if (speaking) onIntent(ChatIntent.SetVoiceState(VoiceState.SPEAKING))
                else if (uiState.isVoiceModeActive) onIntent(ChatIntent.SetVoiceState(VoiceState.LISTENING))
            },
            onError = { error ->
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            },
            speechRate = settingsManager?.ttsSpeechRate ?: 0.95f,
            voiceName = settingsManager?.ttsVoiceName ?: "",
            language = settingsManager?.language ?: "English"
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

    // Removed fallback one-shot speak as per user request to only speak in live mode

    val view = LocalView.current
    LaunchedEffect(uiState.voiceState) {
        if (uiState.isVoiceModeActive) {
            when (uiState.voiceState) {
                VoiceState.LISTENING -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                VoiceState.THINKING -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                VoiceState.SPEAKING -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                else -> {}
            }
        }
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
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { focusManager.clearFocus() })
        },
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                navigationIcon = {
                    IconButton(onClick = { 
                        focusManager.clearFocus()
                        openDrawer() 
                    }) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    // Backend Selection Dropdown
                    var backendExpanded by remember { mutableStateOf(false) }
                    val currentBackend = uiState.activeBackend ?: settingsManager?.hardwareBackend ?: "Auto"
                    
                    Box {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clickable { 
                                    focusManager.clearFocus()
                                    backendExpanded = true 
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = currentBackend,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Backend",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        
                        DropdownMenu(
                            expanded = backendExpanded,
                            onDismissRequest = { backendExpanded = false }
                        ) {
                            listOf("Auto", "GPU", "CPU").forEach { backend ->
                                DropdownMenuItem(
                                    text = { Text(backend) },
                                    onClick = {
                                        settingsManager?.hardwareBackend = backend
                                        backendExpanded = false
                                        // Trigger a reload of the model with the new backend
                                        val currentModel = settingsManager?.currentModelPath
                                        if (!currentModel.isNullOrEmpty()) {
                                            onIntent(ChatIntent.LoadModel(currentModel))
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // New Chat button
                    IconButton(onClick = { 
                        focusManager.clearFocus()
                        onIntent(ChatIntent.ClearHistory) 
                    }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "New Chat",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Live Voice Mode button
                    IconButton(onClick = { 
                        focusManager.clearFocus()
                        startVoiceMode() 
                    }) {
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
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()) {
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
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 8.dp)
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

            items(uiState.messages, key = { it.id }) { msg ->
                if (msg.isUser) {
                    UserMessageBubble(text = msg.text, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    AssistantMessageBubble(
                        message = msg,
                        onCopy = { clipboardManager.setText(AnnotatedString(msg.text)) },
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
            if (uiState.isGenerating) {
                item(key = "streaming_bubble") {
                    StreamingBubbleItem(
                        streamingState = streamingState,
                        clipboardManager = clipboardManager
                    )
                }
            }
            item(key = "bottom_anchor") { Spacer(Modifier.height(1.dp)) }
        }

        // Input Area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AnimatedVisibility(
                visible = isInputFocused,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut()
            ) {
                Surface(
                    onClick = { focusManager.clearFocus() },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(end = 8.dp).size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = "Add Attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedVisibility(
                        visible = !isInputFocused,
                        enter = expandHorizontally() + fadeIn(),
                        exit = shrinkHorizontally() + fadeOut()
                    ) {
                        Surface(
                            onClick = { focusManager.clearFocus() },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = Color.Transparent
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "Add Attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    BasicTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .onFocusChanged { isInputFocused = it.isFocused },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        minLines = 1,
                        maxLines = 8,
                        enabled = !uiState.isGenerating,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (inputText.isBlank()) {
                                    Text(
                                        "Message AI...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 15.sp
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // Removed spacer since TextField now has horizontal padding

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
                        Surface(
                            onClick = { 
                                focusManager.clearFocus()
                                voiceManager.stopListening() 
                            },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.error
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Stop Listening",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else if (uiState.isGenerating) {
                        Surface(
                            onClick = { 
                                focusManager.clearFocus()
                                onIntent(ChatIntent.StopGeneration) 
                            },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop Generation",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else if (inputText.isNotBlank()) {
                        Surface(
                            onClick = {
                                focusManager.clearFocus()
                                onIntent(ChatIntent.SendMessage(inputText))
                                inputText = ""
                                autoScrollEnabled = true
                            },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = sendBtnColor
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    tint = sendIconColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    } else {
                        // Empty text, show Mic and Live buttons
                        Surface(
                            onClick = {
                                focusManager.clearFocus()
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    voiceManager.startListening()
                                } else {
                                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = Color.Transparent
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice Input", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Surface(
                            onClick = { 
                                focusManager.clearFocus()
                                onIntent(ChatIntent.ActivateVoiceMode) 
                            },
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = Color.White
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Headphones, contentDescription = "Live Voice", tint = Color.Black, modifier = Modifier.size(20.dp))
                            }
                        }
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
fun StreamingBubbleItem(
    streamingState: State<StreamingState>,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager
) {
    val state = streamingState.value
    if (state.rawContent.isNotBlank()) {
        val genMsg = ChatMessage(
            text = state.visibleText,
            isUser = false,
            thoughts = state.thoughts,
            actions = state.actions
        )
        AssistantMessageBubble(
            message = genMsg,
            segments = state.segments,
            onCopy = { clipboardManager.setText(AnnotatedString(state.visibleText)) },
            isStreaming = true
        )
    } else {
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

@Composable
fun UserMessageBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun AssistantMessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    isStreaming: Boolean = false,
    segments: List<StreamingSegment> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = "AI", modifier = Modifier.padding(4.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Assistant", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Column(modifier = Modifier.fillMaxWidth()) {
            // Render thoughts
            if (message.thoughts.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Surface(
                    onClick = { expanded = !expanded },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Psychology, contentDescription = "Thinking", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Thinking...", style = MaterialTheme.typography.labelMedium)
                        }
                        if (expanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            message.thoughts.forEach { thought ->
                                Text(thought, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
            
            // Render actions
            if (message.actions.isNotEmpty()) {
                message.actions.forEach { action ->
                    var actionExpanded by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { actionExpanded = !actionExpanded },
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, contentDescription = "Action", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Searched Web: ${action.query}", style = MaterialTheme.typography.labelMedium)
                            }
                            if (actionExpanded && action.uiSources != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                ParsedMarkdownMessage(text = action.uiSources)
                            }
                        }
                    }
                }
            }

            if (message.text.isNotBlank()) {
                if (isStreaming && segments.isNotEmpty()) {
                    // Per-segment rendering: each prose/table section has its own
                    // Compose identity (key) so only the changed segment recomposes.
                    SegmentedStreamingContent(segments = segments)
                } else {
                    // Finalized message: full Markwon render (one-time, equality-guarded).
                    val displayText = if (isStreaming) getStableStreamingText(message.text)
                                      else message.text
                    if (displayText.isNotBlank()) ParsedMarkdownMessage(text = displayText)
                }
            }
            
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

    // Track last rendered content so markwon.setMarkdown is ONLY called when
    // the markdown string actually changed. Finalized messages are immutable,
    // so this is effectively a one-time render per message bubble.
    val lastRenderedContent = remember { mutableStateOf("") }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                setTextColor(surfaceColor.toArgb())
                textSize = bodyLargeSize
                setLineSpacing(4f, 1.1f)
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        },
        update = { view ->
            // Equality guard: skip the expensive setMarkdown call if nothing changed.
            if (lastRenderedContent.value != markdown) {
                lastRenderedContent.value = markdown
                markwon.setMarkdown(view, markdown)
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun ParsedMarkdownMessage(text: String) {
    // remember(text): parsing only runs when text changes, not on every recomposition.
    val parts = remember(text) { parseMarkdownParts(text) }
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

// ── Streaming text stabiliser ─────────────────────────────────────────────────

/**
 * Strips any partial (still-being-typed) markdown table row from the end of a
 * streaming text so that Markwon/MarkwonText only re-renders when a COMPLETE
 * row is committed (i.e. a \n is received).
 *
 * Non-table partial lines (regular prose being typed) are kept as-is so the
 * user sees normal text appear character-by-character.
 */
fun getStableStreamingText(text: String): String {
    // If the text already ends with \n, every row is complete — nothing to strip
    if (text.endsWith("\n")) return text

    val lastNewline = text.lastIndexOf('\n')
    val lastLine = if (lastNewline == -1) text else text.substring(lastNewline + 1)

    // Only strip when the incomplete last line is a table row (starts with |)
    return if (lastLine.trimStart().startsWith("|")) {
        if (lastNewline == -1) "" else text.substring(0, lastNewline + 1)
    } else {
        text // regular prose — let it stream character by character
    }
}

// ── Live streaming content parser ─────────────────────────────────────────────

sealed class StreamingPart {
    data class PlainText(val text: String) : StreamingPart()
    data class LiveTable(
        val headers: List<String>,
        val rows: List<List<String>>,
        val partialRow: String? = null
    ) : StreamingPart()
}

/** Split pipe cells from a single markdown table row line. */
fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|")) return listOf(trimmed)
    // Split on | and drop the empty strings at both ends
    return trimmed.split("|")
        .drop(1)
        .let { cells -> if (cells.lastOrNull()?.isBlank() == true) cells.dropLast(1) else cells }
        .map { it.trim() }
}

/** True for rows like `| --- | :--- | ---: |` */
fun isSeparatorRow(line: String): Boolean {
    val content = line.trim().removePrefix("|").removeSuffix("|")
    return content.split("|").all { cell ->
        val c = cell.trim().replace("-", "").replace(":", "").replace(" ", "")
        c.isEmpty()
    }
}

/**
 * Splits streaming text into plain-text segments and live table segments.
 * Only complete lines (terminated by \n) are promoted to the table;
 * the currently-typing partial row is shown as a "typing" hint below the table.
 */
fun parseStreamingContent(text: String): List<StreamingPart> {
    val parts = mutableListOf<StreamingPart>()
    val lines = text.split("\n")
    val textEndsWithNewline = text.endsWith("\n")
    var plainBuffer = StringBuilder()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val isLastLine = i == lines.size - 1
        val isPartialLine = isLastLine && !textEndsWithNewline

        if (line.trim().startsWith("|")) {
            // Flush any pending plain text first
            if (plainBuffer.isNotEmpty()) {
                val segment = plainBuffer.toString().trimEnd('\n')
                if (segment.isNotBlank()) parts.add(StreamingPart.PlainText(segment))
                plainBuffer = StringBuilder()
            }

            // Consume all consecutive pipe lines
            val completeRows = mutableListOf<String>()
            var partialRow: String? = null

            while (i < lines.size) {
                val tLine = lines[i]
                val tIsLast = i == lines.size - 1
                val tIsPartial = tIsLast && !textEndsWithNewline

                if (tLine.trim().startsWith("|")) {
                    if (tIsPartial) {
                        partialRow = tLine.trim()
                    } else {
                        completeRows.add(tLine)
                    }
                    i++
                } else {
                    break
                }
            }

            // Parse the complete rows into a LiveTable
            val headerLine = completeRows.firstOrNull()
            val headers = if (headerLine != null) parseTableRow(headerLine) else emptyList()

            if (headers.isNotEmpty()) {
                val sepIdx = completeRows.indexOfFirst { isSeparatorRow(it) }
                val dataRows = if (sepIdx >= 0) {
                    completeRows.drop(sepIdx + 1).map { parseTableRow(it) }
                } else emptyList()
                parts.add(StreamingPart.LiveTable(headers, dataRows, partialRow))
            } else if (partialRow != null) {
                // Not even a header yet — show raw
                plainBuffer.append(partialRow)
            }
        } else {
            if (!isPartialLine) {
                plainBuffer.append(line).append("\n")
            } else {
                plainBuffer.append(line)
            }
            i++
        }
    }

    if (plainBuffer.isNotEmpty()) {
        val segment = plainBuffer.toString().trimEnd('\n')
        if (segment.isNotBlank()) parts.add(StreamingPart.PlainText(segment))
    }

    return parts.ifEmpty { listOf(StreamingPart.PlainText(text)) }
}

// ── Native Compose live table ─────────────────────────────────────────────────

@Composable
fun LiveMarkdownTable(
    headers: List<String>,
    rows: List<List<String>>,
    partialRow: String? = null
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val colCount = headers.size.coerceAtLeast(1)

    val horizontalScrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScrollState)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .background(headerBg)
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                headers.forEach { header ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .width(120.dp)
                            .padding(horizontal = 8.dp)
                    )
                }
            }

            HorizontalDivider(color = borderColor)

            // Data rows
            rows.forEachIndexed { rowIndex, cells ->
                val rowBg = if (rowIndex % 2 == 0) Color.Transparent
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)

                Row(
                    modifier = Modifier
                        .background(rowBg)
                        .padding(horizontal = 4.dp, vertical = 5.dp)
                ) {
                    repeat(colCount) { colIndex ->
                        Text(
                            text = cells.getOrElse(colIndex) { "" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .width(120.dp)
                                .padding(horizontal = 8.dp)
                        )
                    }
                }

                if (rowIndex < rows.lastIndex) {
                    HorizontalDivider(color = borderColor.copy(alpha = 0.4f))
                }
            }

            // Partially-typed row hint
            if (partialRow != null) {
                HorizontalDivider(color = borderColor.copy(alpha = 0.4f))
                Text(
                    text = partialRow,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// ── Streaming message content (routes to table or plain text) ─────────────────

@Composable
fun StreamingMessageContent(text: String) {
    val parts = remember(text) { parseStreamingContent(text) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { part ->
            when (part) {
                is StreamingPart.PlainText -> Text(
                    text = part.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 24.sp
                )
                is StreamingPart.LiveTable -> LiveMarkdownTable(
                    headers = part.headers,
                    rows = part.rows,
                    partialRow = part.partialRow
                )
            }
        }
    }
}

// ── Per-segment streaming renderer ────────────────────────────────────────────

/**
 * Renders [StreamingSegment] list with a [key] per segment so each prose/table
 * section has its own independent Compose identity.
 *
 * Prose segments:
 *   - Rendered via [MarkwonText] with the built-in equality guard.
 *   - They never recompose once the cursor moves past them.
 *
 * Table segments:
 *   - [StreamingSegment.Table.committedRows] is the per-row state list.
 *   - [MarkwonText] only re-renders when [committedMarkdown] changes, i.e.
 *     when a full row is committed (\n received) — not on every character.
 *   - The [partialRow] (currently being typed) is shown as lightweight
 *     monospace text below the rendered table, updating every character but
 *     cost-free since it's a plain Compose Text(), not AndroidView.
 */
@Composable
fun SegmentedStreamingContent(segments: List<StreamingSegment>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEachIndexed { index, segment ->
            // Use a stable key: segment type + its row/content count.
            // This avoids recreating composables when preceding segments change
            // during streaming mutations.
            val segmentKey = when (segment) {
                is StreamingSegment.Prose -> "prose_$index"
                is StreamingSegment.Table -> "table_${index}_${segment.committedRows.size}"
            }
            key(segmentKey) {
                when (segment) {
                    is StreamingSegment.Prose -> {
                        MarkwonText(
                            markdown = segment.stableText,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is StreamingSegment.Table -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // Committed rows — re-renders only when committedMarkdown changes
                            if (segment.committedMarkdown.isNotBlank()) {
                                MarkwonText(
                                    markdown = segment.committedMarkdown,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            // Partial row — cheap Text(), updates every character
                            if (segment.partialRow.isNotBlank()) {
                                Text(
                                    text = segment.partialRow,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Auto-scroll helper ────────────────────────────────────────────────────────

/**
 * Returns true when the user is within [thresholdPx] pixels of the list bottom.
 *
 * Pixel-based rather than item-count-based so it stays accurate when items
 * have variable height (markdown tables, code blocks, etc.).
 *
 * Behaviour:
 * - User at bottom            → true  → auto-scroll active
 * - User scrolled up > 300px  → false → auto-scroll paused
 * - User scrolls back down    → true  → auto-scroll resumes automatically
 *
 * This is read live inside coroutines (not derivedStateOf), so it is always
 * fresh at the exact moment of the scroll decision — no stale state risk.
 */
fun LazyListState.isNearBottom(thresholdPx: Int = 100): Boolean {
    val layoutInfo = layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    
    // CRITICAL FIX: Ensure the last item on screen is ACTUALLY the last item in the entire list!
    if (lastVisible.index < totalItems - 1) return false
    
    val viewportBottom = layoutInfo.viewportEndOffset
    // lastVisible.offset is the item's top relative to viewport top (can be negative).
    // Adding size gives the item's bottom edge relative to viewport top.
    return (lastVisible.offset + lastVisible.size) >= (viewportBottom - thresholdPx)
}
