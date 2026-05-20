package com.example.llmapp.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
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
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
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
import com.example.llmapp.ui.chat.state.ChatIntent
import com.example.llmapp.ui.chat.state.ChatUiState
import com.example.llmapp.ui.chat.state.VoiceState
import com.example.llmapp.ui.voice.VoiceConversationOverlay
import com.example.llmapp.ChatMessage
import com.example.llmapp.AgentAction
import com.example.llmapp.ui.chat.utils.isNearBottom
import com.example.llmapp.ui.chat.composables.ChatTopBar
import com.example.llmapp.ui.chat.composables.ChatInputBar
import com.example.llmapp.ui.chat.composables.ChatList
import com.example.llmapp.ui.chat.composables.LoadingModelOverlay
import com.example.llmapp.ui.chat.state.StreamingSegment
import com.example.llmapp.ui.chat.state.StreamingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    sessionMessages: List<ChatMessage>,
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

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) {
            isUserScrolling = true
            autoScrollEnabled = false
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && isUserScrolling) {
            autoScrollEnabled = listState.isNearBottom(100)
            isUserScrolling = false
        }
    }

    LaunchedEffect(sessionMessages.size, uiState.isGenerating) {
        if (autoScrollEnabled) {
            val anchorIndex = listState.layoutInfo.totalItemsCount - 1
            if (anchorIndex >= 0) listState.animateScrollToItem(anchorIndex)
        }
    }

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

    LaunchedEffect(uiState.finalDictatedText) {
        if (uiState.finalDictatedText != null) {
            val space = if (inputText.isNotEmpty() && !inputText.endsWith(" ")) " " else ""
            inputText += space + uiState.finalDictatedText
            onIntent(ChatIntent.ClearDictatedText)
        }
    }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Track what triggered the permission request: "dictation" or "voice_mode"
    var pendingPermissionAction by remember { mutableStateOf("voice_mode") }
    // Controls visibility of the "Permission Permanently Denied" rationale dialog
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            when (pendingPermissionAction) {
                "dictation" -> onIntent(ChatIntent.StartDictation)
                else        -> onIntent(ChatIntent.ActivateVoiceMode)
            }
        } else {
            val activity = context as? android.app.Activity
            val shouldShow = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: false
            if (!shouldShow) showPermissionRationaleDialog = true
        }
    }

    // Rationale dialog shown when microphone is permanently blocked
    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRationaleDialog = false },
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            title = { Text("Microphone Access Blocked") },
            text = {
                Text(
                    "You have permanently denied microphone access. " +
                    "To use voice features, please open App Settings and grant " +
                    "the Microphone permission manually."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPermissionRationaleDialog = false
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationaleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Token callback registration — no-op here, ViewModel.onNewLlmToken() handles TTS routing
    DisposableEffect(Unit) {
        onRegisterTokenCallback { _, _ -> /* handled by ViewModel */ }
        onDispose { }
    }

    val view = LocalView.current
    LaunchedEffect(uiState.voiceState) {
        if (uiState.isVoiceModeActive) {
            when (uiState.voiceState) {
                VoiceState.LISTENING -> view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                VoiceState.THINKING  -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                VoiceState.SPEAKING  -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                else -> {}
            }
        }
    }

    /**
     * Central permission gate for all microphone features.
     *
     * State machine:
     *   GRANTED                 → dispatch intent immediately (ConversationEngine starts)
     *   DENIED, rationale=true  → first/second denial, re-show system dialog
     *   DENIED, rationale=false → permanently blocked, show Settings rationale dialog
     */
    fun requestMicPermission(action: String) {
        pendingPermissionAction = action
        when {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Already granted — dispatch intent, ConversationEngine.start() handles the rest
                when (action) {
                    "dictation" -> onIntent(ChatIntent.StartDictation)
                    else        -> onIntent(ChatIntent.ActivateVoiceMode)
                }
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity, Manifest.permission.RECORD_AUDIO
            ) -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Root box so VoiceConversationOverlay can be positioned absolutely
    // over the ENTIRE screen (including top bar / status bar).
    Box(modifier = Modifier.fillMaxSize()) {

        Scaffold(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
            topBar = {
                ChatTopBar(
                    uiState = uiState,
                    settingsManager = settingsManager,
                    openDrawer = openDrawer,
                    onIntent = onIntent
                )
            }
        ) { innerPadding ->

            Box(modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ChatList(
                        listState = listState,
                        sessionMessages = sessionMessages,
                        uiState = uiState,
                        streamingState = streamingState,
                        clipboardManager = clipboardManager,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                    )

                    ChatInputBar(
                        inputText = inputText,
                        onInputTextChange = { inputText = it },
                        uiState = uiState,
                        onIntent = onIntent,
                        requestMicPermission = { action -> requestMicPermission(action) },
                        onSend = {
                            onIntent(ChatIntent.SendMessage(inputText))
                            inputText = ""
                            autoScrollEnabled = true
                        },
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }

                // Loading Overlay
                if (uiState.isLoadingModel) {
                    LoadingModelOverlay()
                }
            }
        } // end Scaffold

        // ── Absolute full-screen Voice Mode overlay ────────────────────────────
        // All voice control goes through intents → ChatViewModel → ConversationEngine.
        // No direct VoiceManager calls here.
        if (uiState.isVoiceModeActive) {
            VoiceConversationOverlay(
                voiceState = uiState.voiceState,
                partialTranscript = uiState.partialTranscript,
                onInterrupt = {
                    // Barge-in: ConversationEngine handles this internally via VAD,
                    // but the UI button also allows manual interruption.
                    onIntent(ChatIntent.SetVoiceState(VoiceState.LISTENING))
                },
                onDismiss = {
                    onIntent(ChatIntent.DeactivateVoiceMode)
                }
            )
        }
    } // end root Box
}
