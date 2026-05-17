package com.example.llmapp.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.search.settings.SearchPreferences
import com.example.llmapp.core.search.settings.SecureSearchStorage
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.settings.composables.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    currentTheme: String,
    onThemeChanged: (String) -> Unit,
    openDrawer: () -> Unit,
    searchPreferences: SearchPreferences,
    secureSearchStorage: SecureSearchStorage
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SystemPromptSection(settingsManager)
            Spacer(Modifier.height(20.dp))

            HardwareBackendSection(settingsManager)
            Spacer(Modifier.height(20.dp))

            InferenceParametersSection(settingsManager)
            Spacer(Modifier.height(20.dp))

            SearchSettingsSection(
                searchPreferences = searchPreferences,
                secureStorage = secureSearchStorage
            )
            Spacer(Modifier.height(20.dp))

            VoiceAndSpeechSection(settingsManager)
            Spacer(Modifier.height(20.dp))

            AppThemeSection(currentTheme, onThemeChanged)
            Spacer(Modifier.height(24.dp))
        }
    }
}
