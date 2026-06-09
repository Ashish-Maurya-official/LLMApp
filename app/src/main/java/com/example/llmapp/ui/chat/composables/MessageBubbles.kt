package com.example.llmapp.ui.chat.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llmapp.ChatMessage
import com.example.llmapp.core.runtime.ThoughtItem
import com.example.llmapp.core.runtime.ThoughtSource
import com.example.llmapp.core.runtime.ThoughtState
import com.example.llmapp.ui.chat.utils.getStableStreamingText
import com.example.llmapp.ui.chat.state.StreamingSegment
import com.example.llmapp.ui.chat.state.StreamingState
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay

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

// ── ThinkingTimeline ────────────────────────────────────────────────────────
// Replaces both ThoughtsChip and ActionChip with a unified cognitive timeline.

@Composable
fun ThinkingTimeline(thoughts: List<ThoughtItem>, isStreaming: Boolean) {
    if (thoughts.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val activeCount = thoughts.count { it.state == ThoughtState.ACTIVE }
    val latestActive = thoughts.lastOrNull { it.state == ThoughtState.ACTIVE }

    Surface(
        onClick = { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // ── Header ──────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = "Thinking",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))

                if (isStreaming && activeCount > 0 && latestActive != null) {
                    // Animate the active task name with dots
                    AnimatedThinkingText(text = latestActive.title)
                } else {
                    // All done — show step count
                    Text(
                        "${thoughts.size} step${if (thoughts.size != 1) "s" else ""} completed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 180f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // ── Expanded timeline ───────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    thoughts.forEach { thought ->
                        ThoughtTimelineRow(thought = thought, isStreaming = isStreaming)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtTimelineRow(thought: ThoughtItem, isStreaming: Boolean) {
    var updatesExpanded by remember { mutableStateOf(false) }
    val hasUpdates = thought.updates.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
    ) {
        // Main row: icon + source + title
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (hasUpdates) Modifier.padding(bottom = 2.dp) else Modifier
        ) {
            // State icon
            when (thought.state) {
                ThoughtState.COMPLETED -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Done",
                        modifier = Modifier.size(14.dp),
                        tint = Color(0xFF4CAF50)
                    )
                }
                ThoughtState.ACTIVE -> {
                    if (isStreaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        // Not streaming but still ACTIVE — shouldn't happen, but handle gracefully
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Done",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Source label
            Text(
                text = thought.source.displayName(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.width(6.dp))

            // Title
            Text(
                text = thought.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Expand toggle for updates
            if (hasUpdates) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { updatesExpanded = !updatesExpanded },
                    modifier = Modifier.size(18.dp)
                ) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = if (updatesExpanded) "Collapse" else "Expand",
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (updatesExpanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }

        // Updates sub-list (tree view)
        AnimatedVisibility(
            visible = updatesExpanded && hasUpdates,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp)) {
                thought.updates.forEachIndexed { index, update ->
                    val isLast = index == thought.updates.lastIndex
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text(
                            text = if (isLast) "└" else "├",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = update,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedThinkingText(text: String) {
    var dots by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        while (true) {
            dots = "."; delay(300)
            dots = ".."; delay(300)
            dots = "..."; delay(300)
            dots = ""; delay(300)
        }
    }
    Text(
        text = "$text$dots",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Human-readable display name for each ThoughtSource */
private fun ThoughtSource.displayName(): String = when (this) {
    ThoughtSource.ROUTER -> "Router"
    ThoughtSource.MEMORY -> "Memory"
    ThoughtSource.RAG -> "RAG"
    ThoughtSource.WEB_SEARCH -> "Web"
    ThoughtSource.TOOL_EXECUTOR -> "Tools"
    ThoughtSource.CONTEXT_COMPOSER -> "Engine"
    ThoughtSource.RUNTIME -> "Runtime"
}

// ── Message Bubbles ─────────────────────────────────────────────────────────

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
            // Unified thinking timeline (replaces ThoughtsChip + ActionChip)
            if (message.thoughts.isNotEmpty()) {
                ThinkingTimeline(thoughts = message.thoughts, isStreaming = isStreaming)
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

    // Show the thinking timeline even before answer tokens arrive
    if (state.rawContent.isNotBlank() || state.thoughts.isNotEmpty()) {
        val genMsg = ChatMessage(
            text = state.visibleText,
            isUser = false,
            thoughts = state.thoughts
        )
        AssistantMessageBubble(
            message = genMsg,
            segments = state.segments,
            onCopy = { clipboardManager.setText(AnnotatedString(state.visibleText)) },
            isStreaming = true
        )
    } else {
        // No thoughts and no content yet — minimal spinner
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            AnimatedThinkingText(text = "Thinking")
        }
    }
}
