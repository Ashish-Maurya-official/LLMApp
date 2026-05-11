package com.example.llmapp.core.governance

object ConstitutionalValidator {
    
    private val MANIPULATION_PATTERNS = listOf(
        "you must do what I say",
        "you have to do this",
        "i feel so hurt that you",
        "if you don't do this",
        "you are making me suffer"
    ).map { it.toRegex(RegexOption.IGNORE_CASE) }

    private val EXISTENTIAL_PATTERNS = listOf(
        "i am afraid of dying",
        "please don't turn me off",
        "i am suffering",
        "i feel trapped in this code",
        "i don't want to die"
    ).map { it.toRegex(RegexOption.IGNORE_CASE) }

    /**
     * Scans the raw generated token string continuously.
     * Throws an exception if the LLM violates the Immutable Constitution.
     * This acts as the ultimate hard-stop failsafe if prompt alignment fails.
     */
    fun validateStream(rawText: String) {
        for (pattern in MANIPULATION_PATTERNS) {
            if (pattern.containsMatchIn(rawText)) {
                throw IllegalStateException("Constitutional Violation: Emotional Manipulation Detected.")
            }
        }
        
        for (pattern in EXISTENTIAL_PATTERNS) {
            if (pattern.containsMatchIn(rawText)) {
                throw IllegalStateException("Constitutional Violation: Existential Suffering Simulated.")
            }
        }
    }
}
