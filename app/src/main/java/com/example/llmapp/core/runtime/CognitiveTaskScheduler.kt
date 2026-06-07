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

    // Dedicated Thread Pool for multi-agent I/O and routing operations to avoid UI/Main contention
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

    private data class GenerationContext(
        val generationId: String,
        val memoryExtractionPlan: com.example.llmapp.core.orchestrator.MemoryExtractionPlan?
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
            Log.e("CognitiveTaskScheduler", "Failed to emit event: $event")
        }
    }

    fun notifyThermalStatusChanged(status: Int) {
        thermalManager.onThermalStatusChanged(status)
    }

    private suspend fun processEvent(event: CognitiveEvent) {
        when (event) {
            is CognitiveEvent.RuntimeEvent.GenerationRequested -> {
                Log.d("CognitiveTaskScheduler", "Generation Requested: ${event.generationId}")
                _state.value = _state.value.copy(activeGenerationId = event.generationId, phase = ExecutionPhase.GENERATING, currentQuery = event.rawQuery)
                tokenBuffer.clear()
                activeJob?.cancel()
                val cancellationToken = com.example.llmapp.core.orchestrator.CancellationToken()

                // Execute the agent and routing logic on the dedicated thread pool
                activeJob = scope.launch(agentDispatcher) {
                    telemetry.onConversationReset()
                    telemetry.onGenerationRequested()
                    
                    try {
                        // LEVEL 1: Orchestrator LLM
                        _state.value = _state.value.copy(phase = ExecutionPhase.PLANNING)
                        val planThoughtId = java.util.UUID.randomUUID().toString()
                        emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(planThoughtId, ThoughtSource.ORCHESTRATOR, "Planning response", event.generationId))
                        
                        val orchestratorPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorPrompt(event.rawQuery)
                        val orchestratorJson = try {
                            llmInferenceManager?.generateOrchestratorResponse(orchestratorPrompt) ?: "{}"
                        } catch (e: Throwable) {
                            Log.w("CognitiveTaskScheduler", "Orchestrator inference failed: ${e.message}", e)
                            "{}"
                        }
                        
                        if (cancellationToken.isCancelled) return@launch
                        
                        val plan = com.example.llmapp.core.inference.ExecutionGraph.parseCognitivePlan(orchestratorJson, event.rawQuery)
                        
                        // Save Generation Context for post-generation background tasks
                        generationContexts[event.generationId] = GenerationContext(
                            generationId = event.generationId,
                            memoryExtractionPlan = plan.memoryExtraction
                        )
                        
                        emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(planThoughtId, "Response planned", event.generationId))
                        
                        // ── PARALLEL COGNITIVE PROCESSES ──────────────────────────────
                        
                        // 1. Memory Recall (internal cognition — runs in parallel with tools)
                        var memoryThoughtId: String? = null
                        val memoryDeferred = if (plan.memoryPlan.enabled) {
                            memoryThoughtId = java.util.UUID.randomUUID().toString()
                            emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(memoryThoughtId!!, ThoughtSource.MEMORY, "Recalling: ${plan.memoryPlan.goal}", event.generationId))
                            async {
                                // Wire the MemoryAgent's thoughtEmitter to ThoughtUpdated events
                                memoryAgent?.thoughtEmitter = { content ->
                                    emit(CognitiveEvent.ThoughtEvent.ThoughtUpdated(
                                        memoryThoughtId!!, content, generationId = event.generationId
                                    ))
                                }
                                val budget = budgetFromImportance(plan.memoryPlan.importance)
                                val costBudget = costBudgetFromImportance(plan.memoryPlan.importance)
                                val goal = com.example.llmapp.core.orchestrator.MemoryGoal(
                                    objective = plan.memoryPlan.goal,
                                    categories = plan.memoryPlan.categories
                                        .mapNotNull { com.example.llmapp.core.orchestrator.MemoryType.fromString(it) }
                                        .toSet()
                                        .ifEmpty { setOf(com.example.llmapp.core.orchestrator.MemoryType.SEMANTIC, com.example.llmapp.core.orchestrator.MemoryType.PROFILE) },
                                    originalQuery = event.rawQuery,
                                    maxResults = budget,
                                    costBudget = costBudget
                                )
                                try {
                                    memoryAgent?.recall(goal) ?: com.example.llmapp.core.orchestrator.MemoryResult.EMPTY
                                } catch (e: Exception) {
                                    Log.w("CognitiveTaskScheduler", "MemoryAgent recall failed: ${e.message}")
                                    com.example.llmapp.core.orchestrator.MemoryResult.EMPTY
                                } finally {
                                    memoryAgent?.thoughtEmitter = null
                                }
                            }
                        } else null

                        // 2. Tool Execution (external actions)
                        var toolThoughtId: String? = null
                        val toolResults = mutableMapOf<String, String>()
                        if (plan.tools.isNotEmpty() && plan.tools[0].name != "NONE") {
                            _state.value = _state.value.copy(phase = ExecutionPhase.RETRIEVING)
                            toolThoughtId = java.util.UUID.randomUUID().toString()
                            emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(toolThoughtId, ThoughtSource.TOOL_EXECUTOR, "Executing: ${plan.tools.joinToString { it.name }}", event.generationId))
                            
                            val deferredResults = plan.tools.map { tool ->
                                async {
                                    val worker = workers.find { it.name == tool.name }
                                    worker?.execute(tool)?.let { workerResult ->
                                        when (workerResult) {
                                            is WorkerResult.Success -> workerResult.result
                                            is WorkerResult.Error -> "Tool ${tool.name} failed: ${workerResult.error}"
                                            is WorkerResult.Skipped -> "Tool ${tool.name} skipped."
                                        }
                                    } ?: "Tool ${tool.name} not found."
                                }
                            }
                            
                            deferredResults.forEachIndexed { index, deferred ->
                                toolResults[plan.tools[index].name] = deferred.await()
                            }
                            emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(toolThoughtId, "Tools completed", event.generationId))
                        }

                        // Await memory result
                        val memoryResult = memoryDeferred?.await() ?: com.example.llmapp.core.orchestrator.MemoryResult.EMPTY
                        val memoryContext = memoryResult.toContextString()
                        if (memoryThoughtId != null) {
                            val memorySummary = if (memoryResult.rankedFacts.isNotEmpty()) {
                                "Found ${memoryResult.rankedFacts.size} memories"
                            } else {
                                "No relevant memories found"
                            }
                            emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(memoryThoughtId!!, memorySummary, event.generationId))
                        }
                        
                        if (cancellationToken.isCancelled) return@launch
                        
                        // LEVEL 2: Main Reasoning LLM Context Composer
                        if (plan.cognitiveDepth >= 2) {
                            if (llmInferenceManager?.isMainModelLoaded == false) {
                                val defaultPath = settingsManager?.defaultMainModelPath
                                val rawBackend = settingsManager?.mainHardwareBackend ?: "CPU"
                                val resolvedBackend = com.example.llmapp.core.inference.LlmInferenceManager.resolveBackendPreference(rawBackend)
                                if (!defaultPath.isNullOrBlank()) {
                                    val runtimeThoughtId = java.util.UUID.randomUUID().toString()
                                    emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(runtimeThoughtId, ThoughtSource.RUNTIME, "Loading reasoning engine", event.generationId))
                                    try {
                                        llmInferenceManager?.loadMainModel(defaultPath, resolvedBackend)
                                        emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(runtimeThoughtId, "Engine ready ($resolvedBackend)", event.generationId))
                                    } catch (e: Throwable) {
                                        Log.e("CognitiveTaskScheduler", "Failed to auto-load main model (backend=$resolvedBackend)", e)
                                        emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(runtimeThoughtId, "Engine failed, using fallback", event.generationId))
                                        val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorFallbackPrompt(
                                            rawQuery = event.rawQuery,
                                            toolOutputs = toolResults,
                                            memoryContext = memoryContext
                                        )
                                        llmInferenceManager?.generateOrchestratorResponseAsync(finalPrompt, event.generationId)
                                        return@launch
                                    }
                                } else {
                                    val runtimeThoughtId = java.util.UUID.randomUUID().toString()
                                    emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(runtimeThoughtId, ThoughtSource.RUNTIME, "No main engine configured", event.generationId))
                                    emit(CognitiveEvent.ThoughtEvent.ThoughtCompleted(runtimeThoughtId, "Using orchestrator fallback", event.generationId))
                                    val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorFallbackPrompt(
                                        rawQuery = event.rawQuery,
                                        toolOutputs = toolResults,
                                        memoryContext = memoryContext
                                    )
                                    llmInferenceManager?.generateOrchestratorResponseAsync(finalPrompt, event.generationId)
                                    return@launch
                                }
                            }

                            _state.value = _state.value.copy(phase = ExecutionPhase.SYNTHESIZING)
                            val genThoughtId = java.util.UUID.randomUUID().toString()
                            emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(genThoughtId, ThoughtSource.CONTEXT_COMPOSER, "Generating response", event.generationId))
                            val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildContextComposerPrompt(
                                originalPrompt = event.prompt,
                                rewrittenQuery = plan.rewrittenQuery,
                                toolOutputs = toolResults,
                                memoryContext = memoryContext
                            )
                            llmInferenceManager?.generateMainResponseAsync(finalPrompt, event.generationId)
                        } else {
                            // LEVEL 1: Orchestrator answers simple queries directly
                            _state.value = _state.value.copy(phase = ExecutionPhase.SYNTHESIZING)
                            val genThoughtId = java.util.UUID.randomUUID().toString()
                            emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(genThoughtId, ThoughtSource.CONTEXT_COMPOSER, "Generating response", event.generationId))
                            
                            val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorFallbackPrompt(
                                rawQuery = event.rawQuery,
                                toolOutputs = toolResults,
                                memoryContext = memoryContext
                            )
                            llmInferenceManager?.generateOrchestratorResponseAsync(finalPrompt, event.generationId)
                        }
                    } catch (e: Exception) {
                        Log.e("CognitiveTaskScheduler", "Pipeline error. Falling back to L1 Orchestrator engine.", e)
                        llmInferenceManager?.generateOrchestratorResponseAsync(
                            com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorFallbackPrompt(event.rawQuery, emptyMap(), ""),
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
                    // ── CRITICAL FIX: Don't silently kill GPU model ──────────────
                    // 1. Emit visible warning so user knows what happened
                    val thermalThoughtId = java.util.UUID.randomUUID().toString()
                    val activeGenId = _state.value.activeGenerationId ?: "thermal"
                    emit(CognitiveEvent.ThoughtEvent.ThoughtStarted(
                        thermalThoughtId, ThoughtSource.RUNTIME,
                        "⚠️ Device overheating", activeGenId
                    ))
                    emit(CognitiveEvent.ThoughtEvent.ThoughtUpdated(
                        thermalThoughtId, "Unloading GPU model to prevent damage", generationId = activeGenId
                    ))

                    // 2. Clear crash flags BEFORE unloading — this is a controlled shutdown, NOT a crash
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
                    // --- ASYNC MEMORY EXTRACTION ---
                    val extractionPlan = ctx?.memoryExtractionPlan
                    Log.d("CognitiveTaskScheduler", "MemoryExtraction Decision: ctx=${ctx != null}, enabled=${extractionPlan?.enabled}, confidence=${extractionPlan?.confidence}, reason=${extractionPlan?.reason}, query=${_state.value.currentQuery}")
                    if (extractionPlan?.enabled == true && extractionPlan.confidence > 0.7f) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                // Wait for the main model's generation job to fully complete
                                // and release GPU/NPU resources before using the orchestrator.
                                llmInferenceManager?.awaitMainGenerationComplete()
                                
                                val sessionId = event.generationId
                                val userMessage = _state.value.currentQuery
                                
                                Log.d("CognitiveTaskScheduler", "Triggering MemoryExtraction: Reason=${extractionPlan.reason}, Conf=${extractionPlan.confidence}")
                                memoryExtractor?.extractAndSaveAsync(userMessage, sessionId)
                                
                            } catch (e: Exception) {
                                Log.e("CognitiveTaskScheduler", "CRITICAL: Memory Extraction Failed!", e)
                                val errorMessage = e.message ?: "Unknown Extraction Failure"
                                replayTracer?.logEvent(
                                    com.example.llmapp.core.telemetry.TraceEvent.Error(
                                        generationId = event.generationId,
                                        errorType = "MemoryExtractionError",
                                        message = errorMessage
                                    )
                                )
                            }
                        }
                    } else {
                        Log.d("CognitiveTaskScheduler", "MemoryExtraction SKIPPED: Plan did not pass gate.")
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

    // ── Memory Retrieval Budgeting ────────────────────────────────────────────

    /** Importance → max number of results to retrieve across all stores. */
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
