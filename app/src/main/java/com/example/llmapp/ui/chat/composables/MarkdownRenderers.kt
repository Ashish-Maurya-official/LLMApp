package com.example.llmapp.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.ui.chat.utils.MessagePart
import com.example.llmapp.ui.chat.utils.StreamingPart
import com.example.llmapp.ui.chat.utils.parseMarkdownParts
import com.example.llmapp.ui.chat.utils.parseStreamingContent
import com.example.llmapp.ui.chat.state.StreamingSegment
import com.example.llmapp.ui.chat.state.WeatherDetails
import com.example.llmapp.ui.chat.composables.WeatherCard

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
fun CodeBlock(language: String, content: String, onCopy: () -> Unit) {
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
                    text = if (language.isBlank()) "code" else language,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray
                )
                IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy Code",
                        tint = Color.LightGray,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                text = content.trimEnd(),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = Color(0xFFD4D4D4),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

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
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth().horizontalScroll(horizontalScrollState)
    ) {
        Column {
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
                        modifier = Modifier.width(120.dp).padding(horizontal = 8.dp)
                    )
                }
            }
            HorizontalDivider(color = borderColor)

            rows.forEachIndexed { rowIndex, cells ->
                val rowBg = if (rowIndex % 2 == 0) Color.Transparent
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                Row(
                    modifier = Modifier.background(rowBg).padding(horizontal = 4.dp, vertical = 5.dp)
                ) {
                    repeat(colCount) { colIndex ->
                        Text(
                            text = cells.getOrElse(colIndex) { "" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(120.dp).padding(horizontal = 8.dp)
                        )
                    }
                }
                if (rowIndex < rows.lastIndex) {
                    HorizontalDivider(color = borderColor.copy(alpha = 0.4f))
                }
            }

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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEachIndexed { index, segment ->
            val segmentKey = when (segment) {
                is StreamingSegment.Prose -> "prose_$index"
                is StreamingSegment.Table -> "table_${index}_${segment.committedRows.size}"
                is StreamingSegment.Weather -> "weather_$index"
            }
            key(segmentKey) {
                when (segment) {
                    is StreamingSegment.Prose -> MarkwonText(
                        markdown = segment.stableText,
                        modifier = Modifier.fillMaxWidth()
                    )
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
