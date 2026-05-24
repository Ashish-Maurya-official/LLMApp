package com.example.llmapp.ui.models.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.ui.models.state.AvailableModel

@Composable
fun ModelCard(
    model: AvailableModel,
    isDownloaded: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onLoad: () -> Unit,
    isLoaded: Boolean = false,
    activeBackend: String? = null
) {
    val isDownloading = progress != null && progress < 1.0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        label = "download_progress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isDownloaded -> MaterialTheme.colorScheme.secondaryContainer
                isDownloading -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Size: ${model.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isDownloaded) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Tags
            if (model.tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.tags.forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                tag,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Download progress bar
            if (isDownloading) {
                Column {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth(),
                        strokeCap = StrokeCap.Round
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(animatedProgress * 100).toInt()}%  downloading...",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isDownloaded) Arrangement.SpaceBetween else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDownloaded) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Model", tint = MaterialTheme.colorScheme.error)
                        }
                        if (isLoaded) {
                            Button(onClick = onLoad, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (activeBackend != null) "Unload ($activeBackend)" else "Unload Model")
                            }
                        } else {
                            Button(onClick = onLoad) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Load Model")
                            }
                        }
                    } else {
                        OutlinedButton(onClick = onDownload) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}
