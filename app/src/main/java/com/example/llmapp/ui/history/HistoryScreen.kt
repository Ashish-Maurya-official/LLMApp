package com.example.llmapp.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.history.ChatHistoryManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    sessions: List<com.example.llmapp.core.database.SessionEntity>,
    onSessionSelected: (com.example.llmapp.core.database.SessionEntity) -> Unit,
    onDeleteSession: (com.example.llmapp.core.database.SessionEntity) -> Unit,
    openDrawer: () -> Unit
) {

    Scaffold(
        topBar = { 
            TopAppBar(
                title = { Text("Chat History") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            ) 
        }
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No previous sessions.", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { session ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSessionSelected(session) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                val dateStr = sdf.format(Date(session.timestamp))
                                Text(
                                    text = dateStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onDeleteSession(session) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Session", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
