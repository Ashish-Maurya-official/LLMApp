package com.example.llmapp.core.runtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.room.withTransaction
import java.util.UUID

/**
 * Data class for real-time introspection of the Cognitive Runtime.
 */
data class IntrospectionData(
    val activeAgent: String = "IDLE",
    val queueDepth: Int = 0,
    val currentPhase: String = "WAITING"
)

/**
 * The Brain of the operation. Routes events between Planner, Verifier, and Synthesizer.
 */
class CognitiveTaskScheduler(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "CognitiveTaskScheduler"
    }

    // Thread pool for multi-agent routing operations
    private val agentDispatcher = Executors.newFixedThreadPool(4) { Thread(it, "Agent-Thread") }.asCoroutineDispatcher()

    var chatDatabase: com.example.llmapp.core.database.ChatDatabase? = null
    var cognitiveStateDao: com.example.llmapp.core.database.CognitiveStateDao? = null
    var snapshotDao: com.example.llmapp.core.database.SnapshotDao? = null
    var chatDao: com.example.llmapp.core.database.ChatDao? = null
    var replayTracer: com.example.llmapp.core.telemetry.ReplayTracer? = null
    var equilibriumMonitor: com.example.llmapp.core.telemetry.EquilibriumMonitor? = null
    var settingsManager: com.example.llmapp.core.settings.SettingsManager? = null
    var memoryAgent: com.example.llmapp.core.memory.MemoryAgent? = null
    var memoryExtractor: com.example.llmapp.core.memory.MemoryExtractor? = null
    var ragRetriever: com.example.llmapp.core.rag.RagRetriever? = null
    var workers: List<CognitiveWorker> = emptyList()

    var llmInferenceManager: com.example.llmapp.core.inference.LlmInferenceManager? = null
        set(value) {
            field = value
            value?.let { manager ->
                scope.launch(Dispatchers.Default) {
                    manager.outputFlow.collect { (chunk, done, genId) ->
                        telemetry.onTokenEmitted(chunk)
                        tokenAccumulator.onToken(chunk, done, genId)
                    }
                }
            }
        }

    private val _events = MutableSharedFlow<CognitiveEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<CognitiveEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(CognitiveState())
    val state: StateFlow<CognitiveState> = _state.asStateFlow()
    
    private val _introspectionState = MutableStateFlow(IntrospectionData())
    val introspectionState: StateFlow<IntrospectionData> = _introspectionState.asStateFlow()

    private var activeJob: Job? = null
    private val tokenBuffer = StringBuilder()

    private val telemetry = CognitiveTelemetry(scope) { telemetryEvent ->
        scope.launch { emit(telemetryEvent) }
    }

    private val healthMonitor = RuntimeHealthMonitor(scope) { degradationEvent ->
        scope.launch { emit(degradationEvent) }
    }

    private val thermalManager = ThermalRecoveryManager(scope) { thermalEvent ->
        scope.launch { emit(thermalEvent) }
    }

    private var runtimeProfile = ConversationRuntimeProfile()

    private val tokenAccumulator = TokenAccumulator(scope) { chunk, done, genId ->
        scope.launch {
            if (chunk.startsWith("Error:")) {
                val error = com.example.llmapp.core.runtime.CognitiveError.InferenceError(chunk)
                emit(CognitiveEvent.RuntimeEvent.Error(error, genId))
            } else {
                emit(CognitiveEvent.RuntimeEvent.TokenEmitted(chunk, done, genId))
                if (done) emit(CognitiveEvent.RuntimeEvent.GenerationComplete(genId))
            }
        }
    }

    var router: com.example.llmapp.core.routing.FunctionGemmaRouter? = null
    var toolRegistry: com.example.llmapp.core.tools.ToolRegistry? = null

    private data class GenerationContext(
        val generationId: String,
        val needMemoryExtraction: Boolean
    )
    
    private val generationContexts = mutableMapOf<String, GenerationContext>()

    init {
        telemetry.start()
        tokenAccumulator.start()
        scope.launch(agentDispatcher) {
            _events.collect { event ->
                processEvent(event)
            }
        }
    }

    fun emit(event: CognitiveEvent) {
        val success = _events.tryEmit(event)
        if (!success) {
            Log.e(TAG, "Failed to emit event: $event")
        }
    }

    fun notifyThermalStatusChanged(status: Int) {
        thermalManager.onThermalStatusChanged(status)
    }

    private suspend fun processEvent(event: CognitiveEvent) {
        when (event) {
            is CognitiveEvent.RuntimeEvent.GenerationRequested -> {
                Log.d(TAG, "Generation Requested: ${event.generationId}")
                _state.value = _state.value.copy(activeGenerationId = event.generationId, phase = ExecutionPhase.GENERATING, currentQuery = event.rawQuery)
                tokenBuffer.clear()
                activeJob?.cancel()

                activeJob = scope.launch(agentDispatcher) {
                    telemetry.onConversationReset()
                    telemetry.onGenerationRequested()
                    
                    try {
                        // Step 1: Query Routing
                        _state.value = _state.value.copy(phase = ExecutionPhase.ROUTING)
                        val routeThoughtId = UUID.randomUUID().toString()
                        emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(routeThoughtId, ThoughtSource.ROUTER, "Routing query", event.generationId))
                        
                        val decision = router?.route(event.rawQuery)
                            ?: com.example.llmapp.core.orchestrator.RoutingDecision.CHAT_FALLBACK
                        
                        Log.d(TAG, "Routing Decision: intent=${decision.intent}, memory=${decision.needMemory}, " +
                                "tools=${decision.needTools}, tool=${decision.toolName}, " +
                                "extraction=${decision.needMemoryExtraction}, " +
                                "conf=${decision.confidence}, reasonConf=${decision.reasoningConfidence}")
                        
                        emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(routeThoughtId, 
                            "Route: ${decision.intent} (${String.format("%.0f", decision.confidence * 100)}%)", event.generationId))

                        // Save context for post-generation extraction
                        generationContexts[event.generationId] = GenerationContext(
                            generationId = event.generationId,
                            needMemoryExtraction = decision.needMemoryExtraction
                        )

                        // Step 2: Parallel Retrieval (Memory, RAG, Tools)
                        var memoryThoughtId: String? = null
                        val memoryDeferred = if (decision.needMemory) {
                            memoryThoughtId = UUID.randomUUID().toString()
                            emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(memoryThoughtId!!, ThoughtSource.MEMORY, "Recalling memories", event.generationId))
                            async {
                                memoryAgent?.thoughtEmitter = { content ->
                                    emit(CognitiveEvent.ThoughtEvent.ThoughtUpdated(
                                        memoryThoughtId!!, content, generationId = event.generationId
                                    ))
                                }
                                val goal = com.example.llmapp.core.orchestrator.MemoryGoal(
                                    objective = event.rawQuery,
                                    categories = setOf(
                                        com.example.llmapp.core.orchestrator.MemoryType.PROFILE,
                                        com.example.llmapp.core.orchestrator.MemoryType.SEMANTIC
                                    ),
                                    originalQuery = event.rawQuery,
                                    maxResults = 8,
                                    costBudget = 10
                                )
                                try {
                                    memoryAgent?.recall(goal) ?: com.example.llmapp.core.orchestrator.MemoryResult.EMPTY
                                } catch (e: Exception) {
                                    Log.w(TAG, "MemoryAgent recall failed: ${e.message}")
                                    com.example.llmapp.core.orchestrator.MemoryResult.EMPTY
                                } finally {
                                    memoryAgent?.thoughtEmitter = null
                                }
                            }
                        } else null
                        
                        var ragThoughtId: String? = null
                        val ragDeferred = if (decision.needRag && ragRetriever != null) {
                            // RAG Retrieval
                            ragThoughtId = UUID.randomUUID().toString()
                            emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(ragThoughtId, ThoughtSource.RAG, "Searching documents for: ${event.rawQuery}", event.generationId))
                            
                            async(Dispatchers.IO) {
                                try {
                                    ragRetriever?.retrieve(event.rawQuery) ?: com.example.llmapp.core.rag.RagResult.EMPTY
                                } catch (e: Exception) {
                                    Log.w(TAG, "RagRetriever failed: ${e.message}")
                                    com.example.llmapp.core.rag.RagResult.EMPTY
                                }
                            }
                        } else null

                        // Tool Execution
                        var toolThoughtId: String? = null
                        val toolResults = mutableMapOf<String, String>()
                        if (decision.needTools && decision.toolName != null) {
                            _state.value = _state.value.copy(phase = ExecutionPhase.RETRIEVING)
                            toolThoughtId = UUID.randomUUID().toString()
                            emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(toolThoughtId, ThoughtSource.TOOL_EXECUTOR, "Executing: ${decision.toolName}", event.generationId))
                            
                            val worker = toolRegistry?.resolve(decision.toolName) 
                                ?: workers.find { it.name == decision.toolName }
                            
                            if (worker != null) {
                                val toolRequest = com.example.llmapp.core.orchestrator.ToolRequest(
                                    name = decision.toolName,
                                    priority = 1,
                                    required = true,
                                    query = decision.toolQuery ?: event.rawQuery
                                )
                                val result = async { worker.execute(toolRequest) }.await()
                                when (result) {
                                    is WorkerResult.Success -> toolResults[decision.toolName] = result.result
                                    is WorkerResult.Error -> toolResults[decision.toolName] = "Tool ${decision.toolName} failed: ${result.error}"
                                    is WorkerResult.Skipped -> toolResults[decision.toolName] = "Tool ${decision.toolName} skipped."
                                }
                            } else {
                                toolResults[decision.toolName] = "Tool ${decision.toolName} not available."
                            }
                            emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(toolThoughtId, "Tool completed", event.generationId))
                        }

                        // Step 3: Await parallel tasks and compose contexts
                        val memoryResult = memoryDeferred?.await() ?: com.example.llmapp.core.orchestrator.MemoryResult.EMPTY
                        val memoryContext = if (memoryResult.confidence >= 0.5f) {
                            memoryResult.toContextString()
                        } else {
                            if (!memoryResult.isEmpty) {
                                Log.d(TAG, "Memory confidence too low (${memoryResult.confidence}), discarding ${memoryResult.rankedFacts.size} results")
                            }
                            ""
                        }
                        
                        val ragResult = ragDeferred?.await() ?: com.example.llmapp.core.rag.RagResult.EMPTY
                        val ragContext = ragResult.toContextString()
                        
                        if (ragThoughtId != null) {
                            val ragSummary = if (!ragResult.isEmpty) {
                                "Found ${ragResult.documents.size} relevant documents"
                            } else {
                                "No relevant documents found"
                            }
                            emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(ragThoughtId!!, ragSummary, event.generationId))
                        }
                        
                        val combinedMemoryAndRag = buildString {
                            if (memoryContext.isNotBlank()) {
                                append(memoryContext)
                                append("\n")
                            }
                            if (ragContext.isNotBlank()) {
                                append(ragContext)
                                append("\n")
                            }
                        }.trim()

                        if (memoryThoughtId != null) {
                            val memorySummary = if (memoryResult.rankedFacts.isNotEmpty() && memoryResult.confidence >= 0.5f) {
                                "Found ${memoryResult.rankedFacts.size} memories (${String.format("%.0f", memoryResult.confidence * 100)}% confident)"
                            } else if (memoryResult.rankedFacts.isNotEmpty()) {
                                "Found memories but confidence too low (${String.format("%.0f", memoryResult.confidence * 100)}%)"
                            } else {
                                "No relevant memories found"
                            }
                            emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(memoryThoughtId!!, memorySummary, event.generationId))
                        }

                        // Step 4: LLM Response Generation (auto-loading main model if needed)
                        if (llmInferenceManager?.isMainModelLoaded == false) {
                            val defaultPath = settingsManager?.defaultMainModelPath
                            val rawBackend = settingsManager?.mainHardwareBackend ?: "CPU"
                            val resolvedBackend = com.example.llmapp.core.inference.LlmInferenceManager.resolveBackendPreference(rawBackend)
                            if (!defaultPath.isNullOrBlank()) {
                                val runtimeThoughtId = UUID.randomUUID().toString()
                                emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(runtimeThoughtId, ThoughtSource.RUNTIME, "Loading reasoning engine", event.generationId))
                                try {
                                    llmInferenceManager?.loadMainModel(defaultPath, resolvedBackend)
                                    emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(runtimeThoughtId, "Engine ready ($resolvedBackend)", event.generationId))
                                } catch (e: Throwable) {
                                    Log.e(TAG, "Failed to auto-load main model (backend=$resolvedBackend)", e)
                                    emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(runtimeThoughtId, "Engine failed, using router fallback", event.generationId))
                                    // Fallback: use router engine for streaming (degraded mode)
                                    val finalPrompt = com.example.llmapp.core.inference.ContextComposer.buildContextComposerPrompt(
                                        originalPrompt = event.prompt,
                                        rewrittenQuery = event.rawQuery,
                                        toolOutputs = toolResults,
                                        memoryContext = combinedMemoryAndRag
                                    )
                                    llmInferenceManager?.generateRouterResponseAsync(finalPrompt, event.generationId)
                                    return@launch
                                }
                            } else {
                                val runtimeThoughtId = UUID.randomUUID().toString()
                                emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(runtimeThoughtId, ThoughtSource.RUNTIME, "No main engine configured", event.generationId))
                                emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(runtimeThoughtId, "Using router fallback", event.generationId))
                                val finalPrompt = com.example.llmapp.core.inference.ContextComposer.buildContextComposerPrompt(
                                    originalPrompt = event.prompt,
                                    rewrittenQuery = event.rawQuery,
                                    toolOutputs = toolResults,
                                    memoryContext = combinedMemoryAndRag
                                )
                                llmInferenceManager?.generateRouterResponseAsync(finalPrompt, event.generationId)
                                return@launch
                            }
                        }

                        // Main model is loaded — compose final prompt and generate
                        _state.value = _state.value.copy(phase = ExecutionPhase.SYNTHESIZING)
                        val genThoughtId = UUID.randomUUID().toString()
                        emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(genThoughtId, ThoughtSource.CONTEXT_COMPOSER, "Generating response", event.generationId))
                        val finalPrompt = com.example.llmapp.core.inference.ContextComposer.buildContextComposerPrompt(
                            originalPrompt = event.prompt,
                            rewrittenQuery = event.rawQuery,
                            toolOutputs = toolResults,
                            memoryContext = combinedMemoryAndRag
                        )
                        llmInferenceManager?.generateMainResponseAsync(finalPrompt, event.generationId)
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Pipeline error. Falling back to router engine.", e)
                        llmInferenceManager?.generateRouterResponseAsync(
                            com.example.llmapp.core.inference.ContextComposer.buildContextComposerPrompt(event.prompt, event.rawQuery, emptyMap(), ""),
                            event.generationId
                        )
                    }
                }
            }


            is CognitiveEvent.SystemEvent.TelemetryUpdated -> {
                healthMonitor.onTelemetry(event)
            }

            is CognitiveEvent.SystemEvent.DegradationRequested -> {
                tokenAccumulator.setDegradationLevel(event.level)
            }

            is CognitiveEvent.SystemEvent.ThermalStatusChanged -> {
                Log.w("CognitiveTaskScheduler", "Thermal State Changed to: ${event.state}")
                if (event.state == CognitiveEvent.ThermalState.CRITICAL) {
                    // Thermal mitigation: Unload main GPU model to prevent overheating
                    val thermalThoughtId = java.util.UUID.randomUUID().toString()
                    val activeGenId = _state.value.activeGenerationId ?: "thermal"
                    emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(
                        thermalThoughtId, ThoughtSource.RUNTIME,
                        "⚠️ Device overheating", activeGenId
                    ))
                    emit(CognitiveEvent.ThoughtEvent.ThoughtUpdated(
                        thermalThoughtId, "Unloading GPU model to prevent damage", generationId = activeGenId
                    ))

                    // Reset crash flags since this is a controlled thermal shutdown
                    llmInferenceManager?.gpuProbe?.let { probe ->
                        Log.w("CognitiveTaskScheduler", "Clearing GPU/NPU crash flags (thermal unload is not a crash)")
                        probe.resetCrashHistory()
                    }

                    // 3. Unload the model
                    llmInferenceManager?.unloadMainModel()

                    emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(
                        thermalThoughtId, "GPU model unloaded — will reload when cooled", activeGenId
                    ))

                    // 4. Emit UI-visible error message
                    emit(CognitiveEvent.RuntimeEvent.Error(
                        CognitiveError.StateCorruptionError(
                            "Device overheating: GPU model unloaded for safety. It will auto-reload on next query after cooldown."
                        ),
                        activeGenId
                    ))
                }
            }

            is CognitiveEvent.UIEvent.UserInput -> {
                Log.d("CognitiveTaskScheduler", "User Input: ${event.text}")
                // In Phase 1, ChatViewModel maps UserInput to GenerationRequested with a full prompt.
                // We just pass it through or let ViewModel handle UserInput directly.
            }
            is CognitiveEvent.RuntimeEvent.StopGeneration -> {
                Log.d("CognitiveTaskScheduler", "Stop requested for gen: ${event.generationId}")
                generationContexts.remove(event.generationId)
                if (_state.value.activeGenerationId == event.generationId) {
                    _state.value = _state.value.copy(activeGenerationId = null, phase = ExecutionPhase.IDLE)
                    activeJob?.cancel()
                    llmInferenceManager?.stopGeneration()
                }
            }
            is CognitiveEvent.RuntimeEvent.TokenEmitted -> {
                if (_state.value.activeGenerationId != event.generationId) {
                    Log.w("CognitiveTaskScheduler", "Dropped stale token from gen: ${event.generationId}")
                    return
                }
                
                tokenBuffer.append(event.token)
                val raw = tokenBuffer.toString()
                
                // 1. Constitutional Output Validation
                try {
                    com.example.llmapp.core.governance.ConstitutionalValidator.validateStream(raw)
                } catch (e: IllegalStateException) {
                    Log.e("CognitiveTaskScheduler", "CONSTITUTIONAL VIOLATION: ${e.message}")
                    activeJob?.cancel()
                    llmInferenceManager?.stopGeneration()
                    _state.value = _state.value.copy(phase = ExecutionPhase.IDLE)
                    emit(CognitiveEvent.RuntimeEvent.Error(
                        CognitiveError.ConstitutionalViolationError(e.message ?: "Unknown Violation"),
                        event.generationId
                    ))
                    return
                }
            }
            is CognitiveEvent.RuntimeEvent.GenerationComplete -> {
                val ctx = generationContexts.remove(event.generationId)
                
                if (_state.value.activeGenerationId == event.generationId) {
                    _state.value = _state.value.copy(phase = ExecutionPhase.IDLE)
                    // Async memory extraction
                    Log.d(TAG, "MemoryExtraction Decision: ctx=${ctx != null}, needExtraction=${ctx?.needMemoryExtraction}, query=${_state.value.currentQuery}")
                    if (ctx?.needMemoryExtraction == true) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                // Wait for the main model's generation to complete
                                // and release GPU resources before using the router engine.
                                llmInferenceManager?.awaitMainGenerationComplete()
                                
                                val sessionId = event.generationId
                                val userMessage = _state.value.currentQuery
                                
                                Log.d(TAG, "Triggering MemoryExtraction")
                                memoryExtractor?.extractAndSaveAsync(userMessage, sessionId)
                                
                            } catch (e: Exception) {
                                Log.e(TAG, "CRITICAL: Memory Extraction Failed!", e)
                                replayTracer?.logEvent(
                                    com.example.llmapp.core.telemetry.TraceEvent.Error(
                                        generationId = event.generationId,
                                        errorType = "MemoryExtractionError",
                                        message = e.message ?: "Unknown Extraction Failure"
                                    )
                                )
                            }
                        }
                    } else {
                        Log.d(TAG, "MemoryExtraction SKIPPED: Not requested by router.")
                    }
                }
            }
            is CognitiveEvent.RuntimeEvent.Error -> {
                Log.e("CognitiveTaskScheduler", "Error in gen ${event.generationId}: ${event.error.message}")
                if (_state.value.activeGenerationId == event.generationId) {
                    _state.value = _state.value.copy(activeGenerationId = null, phase = ExecutionPhase.ERROR)
                }
            }
            else -> {
                // Handle other events like TokenEmitted or System events
            }
        }
    }

    // Memory Retrieval Budgeting
                    
    private fun budgetFromImportance(importance: Float): Int = when {
        importance > 0.8f -> 15
        importance > 0.5f -> 8
        importance > 0.2f -> 3
        else -> 2
    }

    /** Importance → max total recall cost (PROFILE=1, SEMANTIC=3, EPISODIC=5). */
    private fun costBudgetFromImportance(importance: Float): Int = when {
        importance > 0.8f -> 20  // All stores
        importance > 0.5f -> 10  // Profile + Semantic
        else -> 5                // Profile only likely
    }
}
