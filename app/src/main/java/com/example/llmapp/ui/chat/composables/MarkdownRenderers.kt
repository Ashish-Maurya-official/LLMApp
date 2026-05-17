package com.example.llmapp.ui.chat.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.ui.chat.utils.MessagePart
import com.example.llmapp.ui.chat.utils.StreamingPart
import com.example.llmapp.ui.chat.utils.parseMarkdownParts
import com.example.llmapp.ui.chat.utils.parseStreamingContent
import com.example.llmapp.ui.chat.state.StreamingSegment

// Dynamic Subpackage Card Imports
import com.example.llmapp.ui.chat.composables.cards.WeatherCard
import com.example.llmapp.ui.chat.composables.cards.LiveMarkdownTable
import com.example.llmapp.ui.chat.composables.cards.CodeBlock
import com.example.llmapp.ui.chat.composables.cards.MarkwonText

/**
 * Coordination layer parsing and drawing Markdown structure segments.
 * Delegates rendering details for Tables, Code terminal shelves, Markwon rich content, and Weather items to dedicated card components.
 */
@Composable
fun ParsedMarkdownMessage(text: String) {
    val parts = remember(text) { parseMarkdownParts(text) }
    val clipboardManager = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part) {
                is MessagePart.PlainMarkdown -> MarkwonText(
                    markdown = part.content.trim(),
                    modifier = Modifier.fillMaxWidth()
                )
                is MessagePart.Code -> CodeBlock(
                    language = part.language,
                    content = part.content,
                    onCopy = { clipboardManager.setText(AnnotatedString(part.content)) }
                )
                is MessagePart.Weather -> WeatherCard(
                    details = part.details
                )
            }
        }
    }
}

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

@Composable
fun SegmentedStreamingContent(segments: List<StreamingSegment>) {
    val clipboardManager = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEachIndexed { index, segment ->
            val segmentKey = when (segment) {
                is StreamingSegment.Prose -> "prose_${index}"
                is StreamingSegment.Table -> "table_${index}_${segment.committedRows.size}"
                is StreamingSegment.Weather -> "weather_$index"
            }
            key(segmentKey) {
                when (segment) {
                    is StreamingSegment.Prose -> {
                        // Parse prose markdown statefully to support rendering macOS Terminal cards dynamically while generating
                        val proseParts = remember(segment.stableText) { parseMarkdownParts(segment.stableText) }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            proseParts.forEach { part ->
                                when (part) {
                                    is MessagePart.PlainMarkdown -> MarkwonText(
                                        markdown = part.content.trim(),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    is MessagePart.Code -> CodeBlock(
                                        language = part.language,
                                        content = part.content,
                                        onCopy = { clipboardManager.setText(AnnotatedString(part.content)) }
                                    )
                                    is MessagePart.Weather -> WeatherCard(
                                        details = part.details
                                    )
                                }
                            }
                        }
                    }
                    is StreamingSegment.Weather -> WeatherCard(
                        details = segment.details
                    )
                    is StreamingSegment.Table -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (segment.committedMarkdown.isNotBlank()) {
                            MarkwonText(
                                markdown = segment.committedMarkdown,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
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
