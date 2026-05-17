package com.example.llmapp.ui.models.composables

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ModelHeader(
    isLoadingCatalog: Boolean,
    catalogSource: String,
    onOpenEvaluation: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "Download and run AI models completely offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isLoadingCatalog) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Fetching latest models...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (catalogSource.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(catalogSource, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
        }
        
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onOpenEvaluation,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Run Cognitive Benchmark (Phase 7)")
        }
    }
}
