package com.example.llmapp.ui.chat.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    val activeBackend = uiState.activeBackend

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
            // Read-only backend status chip (no selection — that's done in ModelScreen now)
            if (activeBackend != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = activeBackend,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
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
