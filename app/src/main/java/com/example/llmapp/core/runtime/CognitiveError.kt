package com.example.llmapp.core.runtime

sealed class CognitiveError : Exception() {
    class HardwareError(message: String, cause: Throwable? = null) : CognitiveError()
    class InferenceError(message: String, cause: Throwable? = null) : CognitiveError()
    class GenerationOwnershipError(message: String) : CognitiveError()
    class ResourceExhaustedError(message: String) : CognitiveError()
}
