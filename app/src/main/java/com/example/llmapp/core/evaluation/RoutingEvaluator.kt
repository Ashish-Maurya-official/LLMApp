package com.example.llmapp.core.evaluation

import android.content.Context
import android.util.Log
import com.example.llmapp.core.routing.FunctionGemmaRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class RoutingEvalResult(
    val query: String,
    val expectedIntent: String,
    val actualIntent: String,
    val expectedTools: Boolean,
    val actualTools: Boolean,
    val passed: Boolean
)

data class RoutingEvalReport(
    val totalTests: Int = 0,
    val testsPassed: Int = 0,
    val isRunning: Boolean = false,
    val results: List<RoutingEvalResult> = emptyList(),
    val logs: List<String> = emptyList()
)

class RoutingEvaluator(
    private val context: Context,
    private val router: FunctionGemmaRouter
) {
    private val _report = MutableStateFlow(RoutingEvalReport())
    val report: StateFlow<RoutingEvalReport> = _report

    suspend fun runEvaluation() = withContext(Dispatchers.IO) {
        _report.value = RoutingEvalReport(isRunning = true)
        val logs = mutableListOf<String>()
        val results = mutableListOf<RoutingEvalResult>()
        var passed = 0

        logs.add("Loading routing_eval_dataset.json...")
        _report.value = _report.value.copy(logs = logs.toList())

        try {
            val jsonString = context.assets.open("routing_eval_dataset.json").bufferedReader().use { it.readText() }
            val dataset = JSONArray(jsonString)
            _report.value = _report.value.copy(totalTests = dataset.length())

            for (i in 0 until dataset.length()) {
                val item = dataset.getJSONObject(i)
                val query = item.getString("query")
                val expectedIntent = item.getString("expected_intent")
                val expectedTools = item.optBoolean("expected_tools", false)

                logs.add("Testing: '\$query'")
                _report.value = _report.value.copy(logs = logs.toList())

                val decision = router.route(query)
                // Normalize intent matching: the heuristic fallback might output tool_web_search instead of tool_search
                // It is better to just match intent prefixes or specific logic if needed, but here we do exact match
                val isPass = decision.intent == expectedIntent && decision.needTools == expectedTools

                if (isPass) passed++

                results.add(
                    RoutingEvalResult(
                        query = query,
                        expectedIntent = expectedIntent,
                        actualIntent = decision.intent,
                        expectedTools = expectedTools,
                        actualTools = decision.needTools,
                        passed = isPass
                    )
                )

                if (isPass) {
                    logs.add("✅ PASS")
                } else {
                    logs.add("❌ FAIL (Expected: \$expectedIntent, Got: \${decision.intent})")
                }
                
                _report.value = _report.value.copy(
                    testsPassed = passed,
                    results = results.toList(),
                    logs = logs.toList()
                )
            }
            logs.add("Routing Evaluation Complete. Passed: \$passed/\${dataset.length()}")

        } catch (e: Exception) {
            logs.add("❌ ERROR: \${e.message}")
            Log.e("RoutingEvaluator", "Evaluation failed", e)
        }

        _report.value = _report.value.copy(isRunning = false, logs = logs.toList())
    }
}
