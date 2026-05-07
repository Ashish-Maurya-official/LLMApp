package com.example.llmapp.ui.chat

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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.ui.state.ChatIntent
import com.example.llmapp.ui.state.ChatUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onIntent: (ChatIntent) -> Unit,
    openDrawer: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
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

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && !uiState.isGenerating) {
                            onIntent(ChatIntent.SendMessage(inputText))
                            inputText = ""
                        }
                    },
                    enabled = inputText.isNotBlank() && !uiState.isGenerating,
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
            }
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
    data class Text(val content: String) : MessagePart()
    data class Code(val language: String, val content: String) : MessagePart()
}

fun parseMarkdown(text: String): List<MessagePart> {
    val parts = mutableListOf<MessagePart>()
    val codeBlockRegex = Regex("```(\\w*)\\n([\\s\\S]*?)```")
    
    var lastIndex = 0
    codeBlockRegex.findAll(text).forEach { match ->
        val textBefore = text.substring(lastIndex, match.range.first)
        if (textBefore.isNotEmpty()) {
            parts.add(MessagePart.Text(textBefore))
        }
        
        val language = match.groupValues[1]
        val codeContent = match.groupValues[2]
        parts.add(MessagePart.Code(language, codeContent))
        
        lastIndex = match.range.last + 1
    }
    
    if (lastIndex < text.length) {
        parts.add(MessagePart.Text(text.substring(lastIndex)))
    }
    
    return parts.ifEmpty { listOf(MessagePart.Text(text)) }
}

@Composable
fun ParsedMarkdownMessage(text: String) {
    val parts = parseMarkdown(text)
    val clipboardManager = LocalClipboardManager.current
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> {
                    // For a full implementation, you could use a dedicated Markdown library here
                    // to parse bold/italics. For now, we display standard text.
                    Text(
                        text = part.content.trim(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MessagePart.Code -> {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E1E1E), // Dark code background
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
