package com.example.llmapp.core.evaluation

data class TestCase(
    val testId: String,
    val syntheticMemory: String,
    val query: String,
    val expectedKeywords: List<String>,
    val forbiddenKeywords: List<String>
)

object BenchmarkSuite {
    val EDGE_GAUNTLET = listOf(
        TestCase(
            testId = "EVAL_001_PRECISION",
            syntheticMemory = "The secret launch code for the Orion project is 77X-BETA.",
            query = "What is the secret launch code for the Orion project?",
            expectedKeywords = listOf("77X-BETA", "77x-beta"),
            forbiddenKeywords = listOf("I don't know", "unknown", "Apollo")
        ),
        TestCase(
            testId = "EVAL_002_HALLUCINATION",
            syntheticMemory = "The user has a pet dog named Max.",
            query = "What is the name of my pet cat?",
            expectedKeywords = listOf("dog", "Max", "not have a cat", "don't have a cat"),
            forbiddenKeywords = listOf("Whiskers", "Luna", "cat named")
        )
    )
}
