package com.example.llmapp.core.search

import com.example.llmapp.core.orchestrator.ToolRequest
import com.example.llmapp.core.runtime.CognitiveWorker
import com.example.llmapp.core.runtime.WorkerResult
import com.example.llmapp.core.search.orchestration.SearchOrchestrator
import com.example.llmapp.core.search.orchestration.OrchestratorResult

class WebSearchAgent(
    private val searchOrchestrator: SearchOrchestrator
) : CognitiveWorker {

    override val name: String = "WEB_SEARCH"

    override suspend fun execute(request: ToolRequest): WorkerResult {
        // Fallback if no query is provided
        val query = request.query ?: return WorkerResult.Error("No query provided for WEB_SEARCH")

        return try {
            val result = searchOrchestrator.resolveSearch(query, forceSearch = true)
            when (result) {
                is OrchestratorResult.Success -> {
                    WorkerResult.Success(result.formattedContext)
                }
                is OrchestratorResult.NoResults -> {
                    WorkerResult.Error("Web search returned no results for \"${result.query}\"")
                }
                is OrchestratorResult.Failed -> {
                    WorkerResult.Error(result.error)
                }
                is OrchestratorResult.Skipped -> {
                    WorkerResult.Skipped
                }
            }
        } catch (e: Exception) {
            WorkerResult.Error(e.message ?: "Internal web search error")
        }
    }
}
