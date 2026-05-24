package com.example.llmapp.ui.models.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    onLoadWithBackend: (backend: String) -> Unit,
    onUnload: () -> Unit,
    isLoaded: Boolean = false,
    activeBackend: String? = null,
    defaultBackend: String = "CPU",
    isModelLoading: Boolean = false,
    loadStatus: String = "Ready"
) {
    val isDownloading = progress != null && progress < 1.0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        label = "download_progress"
    )

    // Local state for the backend dropdown
    var selectedBackend by remember(defaultBackend) { mutableStateOf(defaultBackend) }
    var backendDropdownExpanded by remember { mutableStateOf(false) }
    
    // Track if this specific card initiated a load
    var thisCardIsLoading by remember { mutableStateOf(false) }

    // Track whether the user picked a different backend from what's currently active
    val backendChanged = isLoaded && activeBackend != null && selectedBackend != activeBackend

    // When the model gets loaded on a backend, sync the dropdown to match and reset loading
    LaunchedEffect(activeBackend, isLoaded) {
        if (isLoaded && activeBackend != null) {
            selectedBackend = activeBackend
            thisCardIsLoading = false
        }
    }
    
    // Reset loading state when global loading finishes
    LaunchedEffect(isModelLoading) {
        if (!isModelLoading) {
            thisCardIsLoading = false
        }
    }

    val backendOptions = listOf("Auto", "GPU", "NPU", "CPU")
    
    // Is THIS card currently in a loading state?
    val showLoadingOnThisCard = isModelLoading && thisCardIsLoading

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                showLoadingOnThisCard -> MaterialTheme.colorScheme.surfaceContainerHigh
                isLoaded -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                isDownloaded -> MaterialTheme.colorScheme.secondaryContainer
                isDownloading -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header row ──────────────────────────────────────────────
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
                if (isLoaded && activeBackend != null) {
                    // Active badge showing current backend
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = "● $activeBackend",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else if (isDownloaded && !showLoadingOnThisCard) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                } else if (showLoadingOnThisCard) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            // ── Tags ────────────────────────────────────────────────────
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

            // ── Download progress bar ───────────────────────────────────
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
            } else if (isDownloaded) {
                // ── Loading indicator ────────────────────────────────────
                AnimatedVisibility(visible = showLoadingOnThisCard) {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            strokeCap = StrokeCap.Round
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Loading model on $selectedBackend...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // ── Actions row for downloaded models ────────────────────
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Delete button (left side)
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(40.dp),
                            enabled = !showLoadingOnThisCard
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete Model",
                                tint = if (showLoadingOnThisCard)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                else
                                    MaterialTheme.colorScheme.error
                            )
                        }

                        // Backend dropdown selector
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { backendDropdownExpanded = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                enabled = !showLoadingOnThisCard
                            ) {
                                Text(
                                    text = selectedBackend,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Backend",
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = backendDropdownExpanded,
                                onDismissRequest = { backendDropdownExpanded = false }
                            ) {
                                backendOptions.forEach { backend ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(backend)
                                                if (isLoaded && backend == activeBackend) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = MaterialTheme.colorScheme.primary
                                                    ) {
                                                        Text(
                                                            "active",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimary,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedBackend = backend
                                            backendDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        // Load / Unload button
                        when {
                            // Currently loading this model
                            showLoadingOnThisCard -> {
                                FilledTonalButton(
                                    onClick = { },
                                    enabled = false
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Loading...")
                                }
                            }
                            // Not loaded → show Load button
                            !isLoaded -> {
                                Button(
                                    onClick = {
                                        thisCardIsLoading = true
                                        onLoadWithBackend(selectedBackend)
                                    }
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Load")
                                }
                            }
                            // Loaded but user changed backend → show Load (will unload+reload)
                            backendChanged -> {
                                Button(
                                    onClick = {
                                        thisCardIsLoading = true
                                        onLoadWithBackend(selectedBackend)
                                    }
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Load")
                                }
                            }
                            // Loaded on current backend → show Unload button
                            else -> {
                                FilledTonalButton(
                                    onClick = onUnload,
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Unload")
                                }
                            }
                        }
                    }

                    // Hint text when backend changed
                    AnimatedVisibility(visible = backendChanged && !showLoadingOnThisCard) {
                        Text(
                            text = "Will unload from $activeBackend and reload on $selectedBackend",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 48.dp, top = 4.dp)
                        )
                    }
                }
            } else {
                // ── Not downloaded ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
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
