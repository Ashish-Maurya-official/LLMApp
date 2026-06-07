package com.example.llmapp.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmapp.core.database.SemanticMemoryEntity
import com.example.llmapp.core.settings.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    settingsManager: SettingsManager,
    profileViewModel: ProfileViewModel,
    openDrawer: () -> Unit
) {
    var name by remember { mutableStateOf(settingsManager.userName) }
    var dob by remember { mutableStateOf(settingsManager.userDob) }
    var location by remember { mutableStateOf(settingsManager.userLocation) }
    var bio by remember { mutableStateOf(settingsManager.userBio) }
    
    val categorizedMemories by profileViewModel.categorizedMemories.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Profile") },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                "Personalize Your Assistant",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            
            Text(
                "This information helps the AI provide more relevant and personalized responses.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            OutlinedTextField(
                value = name,
                onValueChange = { 
                    name = it
                    settingsManager.userName = it
                },
                label = { Text("Full Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = dob,
                onValueChange = { 
                    dob = it
                    settingsManager.userDob = it
                },
                label = { Text("Date of Birth (e.g., Jan 1, 1990)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = location,
                onValueChange = { 
                    location = it
                    settingsManager.userLocation = it
                },
                label = { Text("Location (City, Country)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = bio,
                onValueChange = { 
                    bio = it
                    settingsManager.userBio = it
                },
                label = { Text("Extra Info / Bio") },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                placeholder = { Text("Tell the assistant more about yourself, your interests, or specific preferences...") }
            )

            Spacer(Modifier.height(24.dp))
            
            Button(
                onClick = { /* Auto-saves on change, but could add explicit save here */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Profile")
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                "Inferred Memories",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )
            Text(
                "Facts the assistant has learned about you autonomously.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(Modifier.height(16.dp))

            if (categorizedMemories.isEmpty()) {
                Text(
                    "No memories extracted yet. Chat with the assistant to let it learn about you!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                categorizedMemories.forEach { (category, memories) ->
                    Text(
                        category,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.align(Alignment.Start).padding(top = 8.dp, bottom = 4.dp)
                    )
                    
                    memories.forEach { memory ->
                        MemoryCard(
                            memory = memory,
                            onForget = { profileViewModel.forgetMemory(memory.id) },
                            onEdit = { newContent -> profileViewModel.updateMemory(memory.id, newContent, memory.originalContent) },
                            onPinToggle = { profileViewModel.togglePin(memory.id, memory.isPinned) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemoryCard(
    memory: SemanticMemoryEntity,
    onForget: () -> Unit,
    onEdit: (String) -> Unit,
    onPinToggle: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf(memory.content) }
    
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    val dateStr = sdf.format(Date(memory.timestamp))
    
    val confidencePct = (memory.confidenceScore * 100).toInt()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (isEditing) {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Edit Fact") }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = { 
                        isEditing = false
                        editContent = memory.content
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                    IconButton(onClick = { 
                        onEdit(editContent)
                        isEditing = false
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            } else {
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Confidence: $confidencePct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Badge(
                                containerColor = if (memory.epistemicState == "PROBABLE") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    memory.epistemicState, 
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                                )
                            }
                        }
                        Text(
                            "Learned: $dateStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (memory.isUserModified) {
                            Text(
                                "User Modified",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }

                    Row {
                        IconButton(onClick = { isEditing = true }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onForget) {
                            Icon(Icons.Default.Delete, contentDescription = "Forget", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = onPinToggle) {
                            Icon(
                                if (memory.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, 
                                contentDescription = "Pin", 
                                modifier = Modifier.size(20.dp),
                                tint = if (memory.isPinned) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    }
                }
            }
        }
    }
}
