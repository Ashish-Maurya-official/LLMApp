package com.example.llmapp.ui.evaluation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmapp.core.evaluation.EvaluationRunner
import com.example.llmapp.core.evaluation.RoutingEvaluator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvaluationScreen(
    runner: EvaluationRunner,
    routingEvaluator: RoutingEvaluator? = null,
    onBack: () -> Unit
) {
    val report by runner.report.collectAsState()
    val routingReport by (routingEvaluator?.report ?: MutableStateFlow(com.example.llmapp.core.evaluation.RoutingEvalReport())).collectAsState()
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
            // Memory Evaluation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Memory Evaluation", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Total Tests: ${report.totalTests}", style = MaterialTheme.typography.bodyMedium)
                    Text("Passed: ${report.testsPassed}", color = MaterialTheme.colorScheme.primary)
                    Text("Hallucinations: ${report.hallucinationCount}", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Routing Evaluation Card
            if (routingEvaluator != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Routing Classification", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Total Tests: ${routingReport.totalTests}", style = MaterialTheme.typography.bodyMedium)
                        Text("Passed: ${routingReport.testsPassed}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { 
                    scope.launch { runner.runGauntlet() } 
                },
                enabled = !report.isRunning && !routingReport.isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (report.isRunning) "Running Memory Gauntlet..." else "Run Memory Benchmark")
            }

            if (routingEvaluator != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { 
                        scope.launch { routingEvaluator.runEvaluation() } 
                    },
                    enabled = !report.isRunning && !routingReport.isRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (routingReport.isRunning) "Running Routing Benchmark..." else "Run Routing Benchmark")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Execution Logs", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))

            val currentLogs = if (routingReport.isRunning || routingReport.logs.isNotEmpty()) routingReport.logs else report.logs

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(currentLogs) { log ->
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
