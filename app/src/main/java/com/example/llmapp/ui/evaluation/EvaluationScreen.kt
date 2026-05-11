package com.example.llmapp.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.evaluation.EvaluationRunner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaluationScreen(
    runner: EvaluationRunner,
    onBack: () -> Unit
) {
    val report by runner.report.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cognitive Evaluation Suite") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Tests: \${report.totalTests}", style = MaterialTheme.typography.titleMedium)
                    Text("Passed: \${report.testsPassed}", color = MaterialTheme.colorScheme.primary)
                    Text("Hallucinations: \${report.hallucinationCount}", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { 
                    scope.launch { runner.runGauntlet() } 
                },
                enabled = !report.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (report.isRunning) "Running Gauntlet..." else "Run Cognitive Benchmark")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Execution Logs", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(report.logs) { log ->
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            log.startsWith("✅") -> MaterialTheme.colorScheme.primary
                            log.startsWith("❌") -> MaterialTheme.colorScheme.error
                            log.startsWith("⚠️") -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
