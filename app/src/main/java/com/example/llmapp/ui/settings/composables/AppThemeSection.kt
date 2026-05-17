package com.example.llmapp.ui.settings.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AppThemeSection(
    currentTheme: String,
    onThemeChanged: (String) -> Unit
) {
    SectionHeader("App Theme")
    val themes = listOf("System", "Light", "Dark")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        themes.forEach { theme ->
            FilterChip(
                selected = currentTheme == theme,
                onClick = { onThemeChanged(theme) },
                label = { Text(theme) }
            )
        }
    }
}
