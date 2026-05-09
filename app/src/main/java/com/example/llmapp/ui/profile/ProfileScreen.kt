package com.example.llmapp.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    settingsManager: SettingsManager,
    openDrawer: () -> Unit
) {
    var name by remember { mutableStateOf(settingsManager.userName) }
    var dob by remember { mutableStateOf(settingsManager.userDob) }
    var location by remember { mutableStateOf(settingsManager.userLocation) }
    var bio by remember { mutableStateOf(settingsManager.userBio) }

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
        }
    }
}
