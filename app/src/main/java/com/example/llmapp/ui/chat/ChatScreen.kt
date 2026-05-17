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
import com.example.llmapp.core.voice.VoiceManager
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
    LaunchedEffect(sessionMessages.size, uiState.isGenerating) {
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

    LaunchedEffect(uiState.finalDictatedText) {
        if (uiState.finalDictatedText != null) {
            val space = if (inputText.isNotEmpty() && !inputText.endsWith(" ")) " " else ""
            inputText += space + uiState.finalDictatedText
            onIntent(ChatIntent.ClearDictatedText)
        }
    }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    // Track what triggered the permission request: "dictation" or "voice_mode"
    var pendingPermissionAction by remember { mutableStateOf("voice_mode") }
    // Controls visibility of the "Permission Permanently Denied" rationale dialog
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

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
            when (pendingPermissionAction) {
                "dictation" -> onIntent(ChatIntent.StartDictation)
                else -> {
                    voiceManager.isVoiceModeActive = true
                    onIntent(ChatIntent.ActivateVoiceMode)
                    voiceManager.startListening()
                }
            }
        } else {
            // Check if the user has permanently denied ("Don't ask again")
            // shouldShowRequestPermissionRationale returns false ONLY in two cases:
            //   1. The user has never been asked (first launch) - we handle this before calling launch()
            //   2. The user has permanently denied it - we must show rationale + Settings link
            val activity = context as? android.app.Activity
            val shouldShow = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO)
            } ?: false

            if (!shouldShow) {
                // Permanently denied: the only path forward is App Settings
                showPermissionRationaleDialog = true
            }
            // If shouldShow == true, the system dialog was shown and denied once.
            // We do nothing; the user can tap the button again to re-trigger.
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
                    // Deep-link directly to this app's permission settings page
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
    /**
     * Central permission gate for all microphone features.
     *
     * State machine:
     *   GRANTED                 → run action immediately
     *   DENIED, rationale=true  → first/second denial, re-show system dialog
     *   DENIED, rationale=false → permanently blocked, show Settings rationale dialog
     */
    fun requestMicPermission(action: String) {
        pendingPermissionAction = action
        when {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Already granted – run immediately
                when (action) {
                    "dictation" -> onIntent(ChatIntent.StartDictation)
                    else -> {
                        voiceManager.resetForNewGeneration()
                        voiceManager.isVoiceModeActive = true
                        onIntent(ChatIntent.ActivateVoiceMode)
                        voiceManager.startListening()
                    }
                }
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity, Manifest.permission.RECORD_AUDIO
            ) -> {
                // Denied once – Android will show the dialog with "Don't ask again" option
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                // Either never asked (show dialog) or permanently denied.
                // We call launch() here; if denied permanently, Android silently ignores it
                // and our launcher callback detects it via shouldShowRationale being false.
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun startVoiceMode() {
        requestMicPermission("voice_mode")
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

        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()) {
            
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
    } // end root Box
}



