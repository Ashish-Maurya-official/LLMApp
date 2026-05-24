package com.example.llmapp.ui.chat.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.chat.state.ChatIntent
import com.example.llmapp.ui.chat.state.ChatUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    uiState: ChatUiState,
    settingsManager: SettingsManager?,
    openDrawer: () -> Unit,
    onIntent: (ChatIntent) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var backendExpanded by remember { mutableStateOf(false) }
    val currentBackend = uiState.activeBackend ?: settingsManager?.mainHardwareBackend ?: "Auto"

    TopAppBar(
        title = { Text("Chat") },
        navigationIcon = {
            IconButton(onClick = { 
                focusManager.clearFocus()
                openDrawer() 
            }) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        },
        actions = {
            // Backend Selection Dropdown
            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable { 
                            focusManager.clearFocus()
                            backendExpanded = true 
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentBackend,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Select Backend",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                
                DropdownMenu(
                    expanded = backendExpanded,
                    onDismissRequest = { backendExpanded = false }
                ) {
                    listOf("Auto", "GPU", "CPU").forEach { backend ->
                        DropdownMenuItem(
                            text = { Text(backend) },
                            onClick = {
                                settingsManager?.mainHardwareBackend = backend
                                backendExpanded = false
                                // Trigger a reload of the model with the new backend
                                val currentModel = settingsManager?.defaultMainModelPath
                                if (!currentModel.isNullOrEmpty()) {
                                    onIntent(ChatIntent.LoadModel(currentModel, false))
                                }
                            }
                        )
                    }
                }
            }

            // New Chat button
            IconButton(onClick = { 
                focusManager.clearFocus()
                onIntent(ChatIntent.ClearHistory) 
            }) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "New Chat",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        modifier = modifier
    )
}
