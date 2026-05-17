package com.example.llmapp.ui.chat.composables.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Renders structured live streaming and static markdown tables inside a scrollable card format.
 */
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
