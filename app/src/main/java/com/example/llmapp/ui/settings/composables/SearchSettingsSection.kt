package com.example.llmapp.ui.settings.composables

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.search.orchestration.SearchOrchestrator
import com.example.llmapp.core.search.providers.GoogleSearchProvider
import com.example.llmapp.core.search.providers.SearchProviderFactory
import com.example.llmapp.core.search.settings.SearchPreferences
import com.example.llmapp.core.search.settings.SecureSearchStorage
import kotlinx.coroutines.launch

@Composable
fun SearchSettingsSection(
    searchPreferences: SearchPreferences,
    secureStorage: SecureSearchStorage
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var webSearchEnabled by remember { mutableStateOf(searchPreferences.webSearchEnabled) }
    var activeProvider by remember { mutableStateOf(searchPreferences.activeProvider) }
    var autoSearch by remember { mutableStateOf(searchPreferences.autoSearch) }
    var maxResults by remember { mutableIntStateOf(searchPreferences.maxResults) }
    var cacheDuration by remember { mutableStateOf(searchPreferences.cacheDuration) }
    var webRetrievalMode by remember { mutableStateOf(searchPreferences.webRetrievalMode) }

    val (savedApiKey, savedCxId) = remember { secureStorage.getGoogleCredentials() }
    var googleApiKey by remember { mutableStateOf(savedApiKey) }
    var googleCxId by remember { mutableStateOf(savedCxId) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var cxIdVisible by remember { mutableStateOf(false) }

    // Validation state
    var validationStatus by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }

    val cacheDurations = listOf("Disabled", "Short (5 min)", "Standard (15 min)", "Long (24 hr)")

    // ── Header ─────────────────────────────────────────────────────────────────
    SectionHeader("Web Search")

    // ── Master toggle ──────────────────────────────────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Enable Web Search", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                if (webSearchEnabled) "AI can fetch live information" else "AI uses internal knowledge only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = webSearchEnabled,
            onCheckedChange = {
                webSearchEnabled = it
                searchPreferences.webSearchEnabled = it
            }
        )
    }

    AnimatedVisibility(visible = webSearchEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(4.dp))

            // ── Provider Dropdown ──────────────────────────────────────────────
            Text("Search Provider", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            SettingsDropdown(
                selectedOption = activeProvider,
                options = SearchProviderFactory.availableProviders,
                onOptionSelected = {
                    activeProvider = it
                    searchPreferences.activeProvider = it
                    validationStatus = ""
                }
            )

            // ── Google Credentials (only shown when Google or Auto selected) ───
            AnimatedVisibility(visible = activeProvider != SearchProviderFactory.PROVIDER_DUCKDUCKGO) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    Text(
                        "Google Custom Search Credentials",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Get your API Key and CX ID from console.cloud.google.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // API Key field
                    OutlinedTextField(
                        value = googleApiKey,
                        onValueChange = { googleApiKey = it; validationStatus = "" },
                        label = { Text("Google API Key") },
                        placeholder = { Text("AIza...") },
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                    Icon(
                                        if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle visibility"
                                    )
                                }
                                if (googleApiKey.isNotBlank()) {
                                    IconButton(onClick = { googleApiKey = ""; secureStorage.saveGoogleApiKey(""); validationStatus = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    // CX ID field
                    OutlinedTextField(
                        value = googleCxId,
                        onValueChange = { googleCxId = it; validationStatus = "" },
                        label = { Text("Search Engine ID (CX)") },
                        placeholder = { Text("e.g. 017576662512468239146:omuauf_lfve") },
                        visualTransformation = if (cxIdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { cxIdVisible = !cxIdVisible }) {
                                    Icon(
                                        if (cxIdVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle visibility"
                                    )
                                }
                                if (googleCxId.isNotBlank()) {
                                    IconButton(onClick = { googleCxId = ""; secureStorage.saveGoogleCxId(""); validationStatus = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    // Save + Validate buttons row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                secureStorage.saveGoogleApiKey(googleApiKey.trim())
                                secureStorage.saveGoogleCxId(googleCxId.trim())
                                validationStatus = "✓ Credentials saved"
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }

                        Button(
                            onClick = {
                                secureStorage.saveGoogleApiKey(googleApiKey.trim())
                                secureStorage.saveGoogleCxId(googleCxId.trim())
                                isValidating = true
                                validationStatus = ""
                                scope.launch {
                                    val provider = GoogleSearchProvider(secureStorage)
                                    validationStatus = provider.validateConfiguration()
                                    isValidating = false
                                }
                            },
                            enabled = googleApiKey.isNotBlank() && googleCxId.isNotBlank() && !isValidating,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isValidating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Testing…")
                            } else {
                                Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Validate")
                            }
                        }
                    }

                    // Validation result chip
                    AnimatedVisibility(visible = validationStatus.isNotBlank()) {
                        val isSuccess = validationStatus.startsWith("✓")
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSuccess)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = null,
                                    tint = if (isSuccess)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    validationStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSuccess)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // ── Behaviour options ──────────────────────────────────────────────
            Text("Behaviour", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Auto-Search", style = MaterialTheme.typography.bodyMedium)
                    Text("AI decides when to search", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = autoSearch,
                    onCheckedChange = { autoSearch = it; searchPreferences.autoSearch = it }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Web Retrieval Mode
            Text("Web Retrieval Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = when(webRetrievalMode) {
                    com.example.llmapp.core.search.settings.WebRetrievalMode.FAST -> "Snippets only. Fastest TTFT."
                    com.example.llmapp.core.search.settings.WebRetrievalMode.BALANCED -> "Selective webpage reading when needed."
                    com.example.llmapp.core.search.settings.WebRetrievalMode.DEEP -> "Aggressive webpage extraction. High latency."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            SettingsDropdown(
                selectedOption = webRetrievalMode.name,
                options = com.example.llmapp.core.search.settings.WebRetrievalMode.values().map { it.name },
                onOptionSelected = {
                    val mode = com.example.llmapp.core.search.settings.WebRetrievalMode.valueOf(it)
                    webRetrievalMode = mode
                    searchPreferences.webRetrievalMode = mode
                }
            )

            Spacer(Modifier.height(8.dp))

            // Max Results Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Results", style = MaterialTheme.typography.bodyMedium)
                    Text("$maxResults", style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = maxResults.toFloat(),
                    onValueChange = {
                        maxResults = it.toInt()
                        searchPreferences.maxResults = it.toInt()
                    },
                    valueRange = 1f..5f,
                    steps = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("1", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("5 (max)", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Cache Duration Dropdown
            Text("Cache Duration", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "Reuse recent results to save API quota",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            SettingsDropdown(
                selectedOption = cacheDuration,
                options = cacheDurations,
                onOptionSelected = { cacheDuration = it; searchPreferences.cacheDuration = it }
            )

            // Info card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Your API keys are stored encrypted on-device and never sent to any external server except the selected search provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
