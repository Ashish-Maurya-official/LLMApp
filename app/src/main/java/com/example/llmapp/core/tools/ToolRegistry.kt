package com.example.llmapp.core.tools

import android.util.Log
import com.example.llmapp.core.orchestrator.ToolRequest
import com.example.llmapp.core.runtime.CognitiveWorker

/**
 * Type-safe tool definitions.
 * Each tool has a unique ID and a human-readable description for the router prompt.
 *
 * Adding a new tool:
 *   1. Add a new object here
 *   2. Implement CognitiveWorker for it
 *   3. Register in MainActivity
 *   4. FunctionGemma will see it via toolManifest()
 */
sealed class Tool(val id: String, val description: String) {
    object WebSearch : Tool("WEB_SEARCH", "Search the internet for real-time information, news, or facts")
    object Flashlight : Tool("FLASHLIGHT", "Toggle the device flashlight on or off")
    object Reminder : Tool("REMINDER", "Set a reminder or alarm for a future time")
    object Weather : Tool("WEATHER", "Get current weather information for a location")
    object Calculator : Tool("CALCULATOR", "Perform mathematical calculations")

    companion object {
        /** All known tools — used for validation and manifest generation */
        val ALL: List<Tool> = listOf(WebSearch, Flashlight, Reminder, Weather, Calculator)

        /** Resolve a tool by its ID string (case-insensitive) */
        fun fromId(id: String): Tool? = ALL.find { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * Central registry for tool workers.
 * Maps sealed Tool definitions to their CognitiveWorker implementations.
 *
 * Usage:
 *   val registry = ToolRegistry()
 *   registry.register(Tool.WebSearch, webSearchAgent)
 *   registry.register(Tool.Flashlight, flashlightWorker)
 *
 *   val worker = registry.resolve("WEB_SEARCH")
 *   worker?.execute(request)
 */
class ToolRegistry {
    companion object {
        private const val TAG = "ToolRegistry"
    }

    private val workers = mutableMapOf<String, CognitiveWorker>()

    /**
     * Register a worker for a tool.
     * Only one worker per tool — last registration wins.
     */
    fun register(tool: Tool, worker: CognitiveWorker) {
        workers[tool.id] = worker
        Log.d(TAG, "Registered worker for tool: ${tool.id}")
    }

    /**
     * Resolve a worker by tool name.
     * Returns null if the tool is not registered (stub tool or unknown).
     */
    fun resolve(toolName: String): CognitiveWorker? {
        return workers[toolName.uppercase()]
    }

    /**
     * All tools that have registered workers (i.e., actually executable).
     */
    fun availableTools(): List<Tool> {
        return Tool.ALL.filter { workers.containsKey(it.id) }
    }

    /**
     * All known tools (including stubs without workers).
     */
    fun allKnownTools(): List<Tool> = Tool.ALL

    /**
     * Generates a tool manifest string for the FunctionGemma router prompt.
     * Only includes tools with registered workers.
     *
     * Example output:
     *   WEB_SEARCH: Search the internet for real-time information, news, or facts
     *   FLASHLIGHT: Toggle the device flashlight on or off
     */
    fun toolManifest(): String {
        return availableTools().joinToString("\n") { "${it.id}: ${it.description}" }
    }

    /**
     * Build a ToolRequest from routing decision fields.
     */
    fun buildToolRequest(toolName: String, toolQuery: String? = null): ToolRequest {
        return ToolRequest(
            name = toolName.uppercase(),
            priority = 1,
            required = true,
            query = toolQuery
        )
    }
}
