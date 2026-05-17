package com.example.llmapp.ui.settings.composables

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    valueFormatter: (Float) -> String = { String.format("%.2f", it) }
) {
    Text("$label: ${valueFormatter(value)}", style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps
    )
}
