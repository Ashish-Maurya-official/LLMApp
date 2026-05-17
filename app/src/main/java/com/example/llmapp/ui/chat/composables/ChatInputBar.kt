package com.example.llmapp.ui.chat.composables

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.ui.chat.state.ChatIntent
import com.example.llmapp.ui.chat.state.ChatUiState

@Composable
fun ChatInputBar(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    uiState: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    requestMicPermission: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var isInputFocused by remember { mutableStateOf(false) }
    
    // Auto-unfocus when keyboard disappears
    val isImeVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    LaunchedEffect(isImeVisible) {
        if (!isImeVisible && isInputFocused) {
            focusManager.clearFocus()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Expandable Add button (only when focused)
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

        // Main input container (Pill)
        Surface(
            modifier = Modifier.weight(1f).defaultMinSize(minHeight = 44.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Add button (only when NOT focused)
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


                // Text Field
                BasicTextField(
                    value = if (uiState.isDictating && uiState.partialTranscript.isNotBlank()) {
                        val space = if (inputText.isNotEmpty() && !inputText.endsWith(" ")) " " else ""
                        inputText + space + uiState.partialTranscript
                    } else {
                        inputText
                    },
                    onValueChange = { 
                        if (!uiState.isDictating) onInputTextChange(it) 
                    },
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
                    enabled = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (inputText.isBlank()) {
                                Text(
                                    "Message AI...",
                                    style = LocalTextStyle.current.copy(
                                        fontSize = 15.sp,
                                        lineHeight = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                // Action Buttons (Send / Stop / Mic / Live)
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

                AnimatedContent(
                    targetState = when {
                        uiState.isDictating -> "dictating"
                        uiState.isGenerating -> "generating"
                        inputText.isNotBlank() -> "send"
                        else -> "idle"
                    },
                    transitionSpec = {
                        (fadeIn() + scaleIn(initialScale = 0.8f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.8f))
                    },
                    label = "ActionButtons"
                ) { state ->
                    when (state) {
                        "dictating" -> {
                            Surface(
                                onClick = { 
                                    focusManager.clearFocus()
                                    onIntent(ChatIntent.StopDictation) 
                                },
                                modifier = Modifier.padding(end = 6.dp).size(32.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop Dictation", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        "generating" -> {
                            Surface(
                                onClick = { 
                                    focusManager.clearFocus()
                                    onIntent(ChatIntent.StopGeneration) 
                                },
                                modifier = Modifier.padding(end = 6.dp).size(32.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.errorContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop Generation", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                        "send" -> {
                            Surface(
                                onClick = {
                                    focusManager.clearFocus()
                                    onSend()
                                },
                                modifier = Modifier.padding(end = 6.dp).size(32.dp),
                                shape = CircleShape,
                                color = sendBtnColor
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = sendIconColor, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                        "idle" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    onClick = {
                                        focusManager.clearFocus()
                                        requestMicPermission("dictation")
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
                                        requestMicPermission("voice_mode")
                                    },
                                    modifier = Modifier.padding(end = 6.dp).size(32.dp),
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
            }
        }
    }
}
