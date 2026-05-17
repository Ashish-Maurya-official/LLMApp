package com.example.llmapp.ui.chat.composables.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Beautiful macOS-style developer terminal code block card.
 * Features red/yellow/green window control dots, centered terminal label tags,
 * side-by-side elegant line numbering, copy success animation alerts, and horizontal scrolls.
 */
@Composable
fun CodeBlock(
    language: String,
    content: String,
    onCopy: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    val cleanLanguage = if (language.isBlank()) "bash" else language.lowercase()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0B0E14), // Premium Pitch Black-Charcoal terminal background
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.03f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column {
            // Header Bar (macOS Terminal Control Panel Style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22)) // Structured terminal header background
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Window Control Buttons (Red, Yellow, Green macOS indicators)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF5F56)))
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFFBD2E)))
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF27C93F)))
                }

                // Centered Terminal Identity Header
                Text(
                    text = "terminal — $cleanLanguage",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.LightGray.copy(alpha = 0.7f)
                )

                // Interactive "Copy" / "Copied!" Trigger Button
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (copied) Color(0xFF27C93F).copy(alpha = 0.15f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .clickable {
                            if (!copied) {
                                onCopy()
                                copied = true
                                coroutineScope.launch {
                                    delay(2000)
                                    copied = false
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy action",
                        tint = if (copied) Color(0xFF27C93F) else Color.LightGray,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (copied) "Copied!" else "Copy",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (copied) Color(0xFF27C93F) else Color.LightGray,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Divider line separating header from source code
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.05f))
            )

            // Terminal Code Workspace (With custom side-by-side IDE line numbers!)
            val lines = remember(content) { content.trimEnd().split("\n") }
            val horizontalScrollState = rememberScrollState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Column A: Line numbering gutter
                Column(
                    modifier = Modifier.padding(end = 14.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    lines.forEachIndexed { index, _ ->
                        Text(
                            text = (index + 1).toString(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 20.sp
                            ),
                            color = Color.LightGray.copy(alpha = 0.25f)
                        )
                    }
                }

                // Column B: Actual scrollable source code lines
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(horizontalScrollState)
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 20.sp
                            ),
                            color = Color(0xFFE6EDF3) // Pristine elegant bright terminal text color
                        )
                    }
                }
            }
        }
    }
}
