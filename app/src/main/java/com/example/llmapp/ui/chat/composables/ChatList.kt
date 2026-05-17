package com.example.llmapp.ui.chat.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.llmapp.ChatMessage
import com.example.llmapp.ui.chat.state.ChatUiState
import com.example.llmapp.ui.chat.state.StreamingState

@Composable
fun ChatList(
    listState: LazyListState,
    sessionMessages: List<ChatMessage>,
    uiState: ChatUiState,
    streamingState: State<StreamingState>,
    clipboardManager: ClipboardManager,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        reverseLayout = false
    ) {
        // Empty state
        if (sessionMessages.isEmpty() && !uiState.isGenerating) {
            item {
                EmptyChatState(status = uiState.status)
            }
        }

        // Message items
        items(
            items = sessionMessages,
            key = { it.id }
        ) { msg ->
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

        // Streaming bubble
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
}

@Composable
fun EmptyChatState(status: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
            if (status == "Ready")
                "Type a message or tap the mic to start a conversation."
            else
                "Open the drawer → Models to download and load a model first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
