package com.example.llmapp.core.runtime

sealed class CognitiveError(message: String? = null) : Exception(message) {
    class HardwareError(message: String, cause: Throwable? = null) : CognitiveError(message)
    class InferenceError(message: String, cause: Throwable? = null) : CognitiveError(message)
    class GenerationOwnershipError(message: String) : CognitiveError(message)
    class ResourceExhaustedError(message: String) : CognitiveError(message)
    data class ToolExecutionError(override val message: String, val toolName: String) : CognitiveError(message)
    data class GenerationPreemptedError(override val message: String = "Generation was forcefully cancelled by OS") : CognitiveError(message)
    data class StateCorruptionError(override val message: String) : CognitiveError(message)
    data class ConstitutionalViolationError(override val message: String) : CognitiveError("[COGNITIVE_FAULT: CONSTITUTION VIOLATION] \$message")
}
