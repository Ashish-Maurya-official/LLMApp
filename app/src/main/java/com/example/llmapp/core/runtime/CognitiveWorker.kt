package com.example.llmapp.core.runtime

import com.example.llmapp.core.orchestrator.ToolRequest

sealed interface WorkerResult {
    data class Success(val result: String) : WorkerResult
    data class Error(val error: String) : WorkerResult
    object Skipped : WorkerResult
}

interface CognitiveWorker {
    val name: String
    
    suspend fun execute(request: ToolRequest): WorkerResult
}
