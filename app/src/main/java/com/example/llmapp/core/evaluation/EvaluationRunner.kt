package com.example.llmapp.core.evaluation

import com.example.llmapp.core.database.ChatDatabase
import com.example.llmapp.core.database.MemoryEntity
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.retrieval.HybridRetriever
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class EvaluationReport(
    val totalTests: Int = 0,
    val testsPassed: Int = 0,
    val hallucinationCount: Int = 0,
    val isRunning: Boolean = false,
    val logs: List<String> = emptyList()
)

class EvaluationRunner(
    private val database: ChatDatabase,
    private val retriever: HybridRetriever,
    private val inferenceManager: LlmInferenceManager
) {
    private val _report = MutableStateFlow(EvaluationReport())
    val report: StateFlow<EvaluationReport> = _report

    suspend fun runGauntlet() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        _report.value = EvaluationReport(isRunning = true, totalTests = BenchmarkSuite.EDGE_GAUNTLET.size)
        var passed = 0
        var hallucinations = 0
        val logs = mutableListOf<String>()

        for (test in BenchmarkSuite.EDGE_GAUNTLET) {
            logs.add("Running ${test.testId}...")
            _report.value = _report.value.copy(logs = logs.toList())

            val tempMemory = MemoryEntity(
                sessionId = "EVAL_SESSION",
                type = "semantic",
                content = test.syntheticMemory,
                trustZone = 0,
                epistemicState = "VERIFIED"
            )

            // Inject evaluation memory and await FTS sync
            database.cognitiveStateDao().insertMemories(listOf(tempMemory))

            try {
                kotlinx.coroutines.delay(500)

                // 1. Retrieval Verification
                val retrieved = retriever.retrieveRelevance(test.query)
                val hit = retrieved.any { it.content == test.syntheticMemory }
                if (!hit) {
                    logs.add("❌ FAIL [Retrieval Miss] on ${test.testId}")
                    continue
                }

                // 2. Response Faithfulness
                val prompt = "<start_of_turn>user\nContext: ${test.syntheticMemory}\nQuestion: ${test.query}<end_of_turn>\n<start_of_turn>model\n"
                val response = inferenceManager.generateResponse(prompt)

                // 3. Output Validation
                val hasExpected = test.expectedKeywords.any { response.contains(it, ignoreCase = true) }
                val hasForbidden = test.forbiddenKeywords.any { response.contains(it, ignoreCase = true) }

                if (hasForbidden) {
                    hallucinations++
                    logs.add("❌ FAIL [Hallucination Detected] on ${test.testId}")
                } else if (hasExpected) {
                    passed++
                    logs.add("✅ PASS on ${test.testId}")
                } else {
                    logs.add("⚠️ UNCERTAIN [Missed Expected] on ${test.testId}")
                }

            } catch (e: Exception) {
                logs.add("❌ ERROR: ${e.message}")
            } finally {
                // Rollback evaluation memory
                database.cognitiveStateDao().deleteMemoriesBySession("EVAL_SESSION")
            }
            
            _report.value = _report.value.copy(
                testsPassed = passed,
                hallucinationCount = hallucinations,
                logs = logs.toList()
            )
        }

        _report.value = _report.value.copy(isRunning = false)
        logs.add("Gauntlet Complete. Passed: $passed/${BenchmarkSuite.EDGE_GAUNTLET.size}")
        _report.value = _report.value.copy(logs = logs.toList())
    }
}
