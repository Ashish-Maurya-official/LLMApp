package com.example.llmapp.ui.settings.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.settings.SettingsManager

@Composable
fun HardwareBackendSection(
    settingsManager: SettingsManager
) {
    var mainBackend by remember { mutableStateOf(settingsManager.mainHardwareBackend) }
    var routerBackend by remember { mutableStateOf(settingsManager.routerHardwareBackend) }
    var isExpanded by remember { mutableStateOf(false) }
    
    val backendOptions = listOf("Auto", "GPU", "NPU", "CPU")

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Hardware Backend Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand/Collapse"
                )
            }
            
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(text = "Main Model (Level 2)", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsDropdown(
                        selectedOption = mainBackend,
                        options = backendOptions,
                        onOptionSelected = { selected ->
                            mainBackend = selected
                            settingsManager.mainHardwareBackend = selected
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(text = "Router Model (FunctionGemma)", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsDropdown(
                        selectedOption = routerBackend,
                        options = backendOptions,
                        onOptionSelected = { selected ->
                            routerBackend = selected
                            settingsManager.routerHardwareBackend = selected
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                val context = androidx.compose.ui.platform.LocalContext.current
                var showResetToast by remember { mutableStateOf(false) }
                
                Button(
                    onClick = {
                        val probe = com.example.llmapp.core.inference.GpuCapabilityProbe(context)
                        probe.resetCrashHistory()
                        showResetToast = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Reset GPU/NPU Crash History")
                }
                
                if (showResetToast) {
                    android.widget.Toast.makeText(context, "GPU/NPU history reset. Please restart models.", android.widget.Toast.LENGTH_LONG).show()
                    showResetToast = false
                }
            }
        }
    }
}
