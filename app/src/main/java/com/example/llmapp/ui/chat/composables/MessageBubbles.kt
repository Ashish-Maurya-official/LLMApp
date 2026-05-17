package com.example.llmapp.ui.chat.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.llmapp.AgentAction
import com.example.llmapp.ChatMessage
import com.example.llmapp.ui.chat.utils.getStableStreamingText
import com.example.llmapp.ui.chat.state.StreamingSegment
import com.example.llmapp.ui.chat.state.StreamingState
import androidx.compose.runtime.State

@Composable
fun UserMessageBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
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
fun ThoughtsChip(thoughts: List<String>) {
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
                thoughts.forEach { thought ->
                    Text(
                        thought,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun ActionChip(action: AgentAction) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = !expanded },
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
            if (expanded && action.uiSources != null) {
                Spacer(modifier = Modifier.height(4.dp))
                ParsedMarkdownMessage(text = action.uiSources)
            }
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
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            ) {
                Icon(
                    Icons.Default.SmartToy,
                    contentDescription = "AI",
                    modifier = Modifier.padding(4.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Assistant",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (message.thoughts.isNotEmpty()) {
                ThoughtsChip(thoughts = message.thoughts)
            }

            message.actions.forEach { action ->
                ActionChip(action = action)
            }

            if (message.text.isNotBlank()) {
                if (isStreaming && segments.isNotEmpty()) {
                    SegmentedStreamingContent(segments = segments)
                } else {
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
