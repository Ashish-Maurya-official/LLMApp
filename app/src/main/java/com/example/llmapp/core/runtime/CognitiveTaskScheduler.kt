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
    private val SENTINEL_REGEX = Regex("""\[SEARCH_NEEDED(?::\s*([^\]]+))?\]""")

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
                _state.value = _state.value.copy(activeGenerationId = event.generationId, phase = ExecutionPhase.GENERATING)
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
                        emit(CognitiveEvent.RuntimeEvent.TokenEmitted("<thought>Level 1: Orchestrator Active...</thought>\n", false, event.generationId))
                        
                        val orchestratorPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorPrompt(event.rawQuery)
                        // FIX(BUG 6): generateOrchestratorResponse is now a suspend fun
                        // (was runBlocking which could deadlock the agentDispatcher pool)
                        val orchestratorJson = try {
                            llmInferenceManager?.generateOrchestratorResponse(orchestratorPrompt) ?: "{}"
                        } catch (e: Throwable) {
                            Log.w("CognitiveTaskScheduler", "Orchestrator inference failed: ${e.message}", e)
                            "{}"
                        }
                        
                        if (cancellationToken.isCancelled) return@launch
                        
                        val plan = com.example.llmapp.core.inference.ExecutionGraph.parseCognitivePlan(orchestratorJson, event.rawQuery)
                        
                        // PARALLEL TOOL EXECUTION (Execution Graph)
                        val toolResults = mutableMapOf<String, String>()
                        if (plan.tools.isNotEmpty() && plan.tools[0].name != "NONE") {
                            _state.value = _state.value.copy(phase = ExecutionPhase.RETRIEVING)
                            emit(CognitiveEvent.RuntimeEvent.TokenEmitted("<thought>Executing tools in parallel: ${plan.tools.joinToString { it.name }}</thought>\n", false, event.generationId))
                            
                            val deferredResults = plan.tools.map { tool ->
                                async {
                                    if (tool.name == "WEB_SEARCH") {
                                        val skill = com.example.llmapp.core.skills.WebSearchSkill()
                                        skill.search(plan.rewrittenQuery).second
                                    } else {
                                        "Tool ${tool.name} executed."
                                    }
                                }
                            }
                            
                            deferredResults.forEachIndexed { index, deferred ->
                                toolResults[plan.tools[index].name] = deferred.await()
                            }
                        }
                        
                        if (cancellationToken.isCancelled) return@launch
                        
                        // LEVEL 2: Main Reasoning LLM Context Composer
                        if (plan.cognitiveDepth >= 2) {
                            if (llmInferenceManager?.isMainModelLoaded == false) {
                                val defaultPath = settingsManager?.defaultMainModelPath
                                val rawBackend = settingsManager?.mainHardwareBackend ?: "CPU"
                                // Pre-resolve backend to prevent native GPU crashes during auto-load
                                val resolvedBackend = com.example.llmapp.core.inference.LlmInferenceManager.resolveBackendPreference(rawBackend)
                                if (!defaultPath.isNullOrBlank()) {
                                    emit(CognitiveEvent.RuntimeEvent.TokenEmitted("<thought>Auto-loading Main Engine for complex task ($resolvedBackend backend, requested: $rawBackend)...</thought>\n", false, event.generationId))
                                    try {
                                        llmInferenceManager?.loadMainModel(defaultPath, resolvedBackend)
                                    } catch (e: Throwable) {
                                        Log.e("CognitiveTaskScheduler", "Failed to auto-load main model (backend=$resolvedBackend)", e)
                                        emit(CognitiveEvent.RuntimeEvent.TokenEmitted("\n<thought>Failed to load Main Engine ($resolvedBackend): ${e.message}. Falling back to Orchestrator for response.</thought>\n", false, event.generationId))
                                        val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorFallbackPrompt(
                                            rawQuery = event.rawQuery,
                                            toolOutputs = toolResults,
                                            memoryContext = ""
                                        )
                                        llmInferenceManager?.generateOrchestratorResponseAsync(finalPrompt, event.generationId)
                                        return@launch
                                    }
                                } else {
                                    emit(CognitiveEvent.RuntimeEvent.TokenEmitted("\n<thought>Main Engine not configured. Falling back to Orchestrator for response.</thought>\n", false, event.generationId))
                                    val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorFallbackPrompt(
                                        rawQuery = event.rawQuery,
                                        toolOutputs = toolResults,
                                        memoryContext = ""
                                    )
                                    llmInferenceManager?.generateOrchestratorResponseAsync(finalPrompt, event.generationId)
                                    return@launch
                                }
                            }

                            _state.value = _state.value.copy(phase = ExecutionPhase.SYNTHESIZING)
                            val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildContextComposerPrompt(
                                originalPrompt = event.prompt,
                                rewrittenQuery = plan.rewrittenQuery,
                                toolOutputs = toolResults,
                                memoryContext = "" // Can wire memory consolidator here in the future
                            )
                            llmInferenceManager?.generateMainResponseAsync(finalPrompt, event.generationId)
                        } else {
                            // LEVEL 1: Orchestrator answers simple queries directly
                            _state.value = _state.value.copy(phase = ExecutionPhase.SYNTHESIZING)
                            
                            val thoughtMessage = if (toolResults.isNotEmpty()) {
                                "\n<thought>Background tasks completed. Orchestrator generating natural response...</thought>\n"
                            } else {
                                "\n<thought>Query is basic. Orchestrator generating response directly...</thought>\n"
                            }
                            emit(CognitiveEvent.RuntimeEvent.TokenEmitted(thoughtMessage, false, event.generationId))
                            
                            val finalPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildOrchestratorFallbackPrompt(
                                rawQuery = event.rawQuery,
                                toolOutputs = toolResults,
                                memoryContext = ""
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
            is CognitiveEvent.ToolEvent.SearchCompleted -> {
                Log.d("CognitiveTaskScheduler", "SearchCompleted: resetting phase to IDLE for re-generation.")
                // Reset phase so the sentinel loop guard allows processing the new generation cleanly.
                _state.value = _state.value.copy(phase = ExecutionPhase.IDLE)
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
                    llmInferenceManager?.unloadMainModel()
                }
            }

            is CognitiveEvent.UIEvent.UserInput -> {
                Log.d("CognitiveTaskScheduler", "User Input: ${event.text}")
                // In Phase 1, ChatViewModel maps UserInput to GenerationRequested with a full prompt.
                // We just pass it through or let ViewModel handle UserInput directly.
            }
            is CognitiveEvent.RuntimeEvent.StopGeneration -> {
                Log.d("CognitiveTaskScheduler", "Stop requested for gen: ${event.generationId}")
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
                
                // Sentinel detection (Web Search Tool)
                // ONLY trigger on the explicit [SEARCH_NEEDED] token — never on refusal phrases.
                // Refusal phrases fire on EVERY response (including post-search ones), causing
                // an infinite loop: LLM -> refusal detected -> search -> LLM -> refusal detected -> ...
                val sentinelMatch = SENTINEL_REGEX.find(raw)
                val needsSearch = sentinelMatch != null
                
                // Loop guard: only trigger if we are NOT already mid-retrieval
                if (needsSearch && _state.value.phase != ExecutionPhase.RETRIEVING) {
                    val extractedQuery = sentinelMatch!!.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: ""
                    Log.d("CognitiveTaskScheduler", "[SEARCH_NEEDED] sentinel detected. Query: '$extractedQuery'")
                    
                    // Stop LLM immediately
                    activeJob?.cancel()
                    llmInferenceManager?.stopGeneration()
                    _state.value = _state.value.copy(phase = ExecutionPhase.RETRIEVING)
                    tokenBuffer.clear()
                    emit(CognitiveEvent.ToolEvent.SearchRequested(extractedQuery, event.generationId))
                }
            }
            is CognitiveEvent.RuntimeEvent.GenerationComplete -> {
                if (_state.value.activeGenerationId == event.generationId) {
                    _state.value = _state.value.copy(phase = ExecutionPhase.IDLE)
                    
                    // --- ATOMIC COGNITIVE STATE COMMIT ---
                    scope.launch(Dispatchers.IO) {
                        try {
                            val sessionId = _state.value.activeGenerationId ?: "unknown"
                            
                            // Mocking an inferred semantic memory for testing
                            val newMemories = listOf(
                                com.example.llmapp.core.database.MemoryEntity(
                                    sessionId = sessionId,
                                    type = "semantic",
                                    content = "Agent successfully generated a response for session $sessionId.",
                                    trustZone = 2 // Agent Inferred
                                )
                            )
                            val newGoals = emptyList<com.example.llmapp.core.database.GoalEntity>()

                            // Calculate epistemic hash
                            val recentMemories = chatDao?.getMemoriesByType("semantic") ?: emptyList()
                            val stateHash = EpistemicLedger.calculateStateHash(recentMemories + newMemories)
                            
                            val snapshot = com.example.llmapp.core.database.CognitiveSnapshotEntity(
                                sessionId = sessionId,
                                epistemicStateHash = stateHash,
                                version = 1
                            )

                            // Execute Atomic Transaction using Room's native coroutine extension
                            chatDatabase?.withTransaction {
                                if (newMemories.isNotEmpty()) {
                                    val safeToProceed = equilibriumMonitor?.logMemoryMutation() ?: true
                                    if (safeToProceed) {
                                        cognitiveStateDao?.insertMemories(newMemories)
                                    } else {
                                        // Emit an Error to trigger cool-down if stuck in a hallucination loop
                                        throw IllegalStateException("Equilibrium Mutability Threshold Exceeded")
                                    }
                                }
                                if (newGoals.isNotEmpty()) cognitiveStateDao?.insertGoals(newGoals)
                                cognitiveStateDao?.insertSnapshot(snapshot)
                            }
                            Log.d("CognitiveTaskScheduler", "Atomic state committed successfully: \$stateHash")
                            
                            // Log Trace
                            replayTracer?.logEvent(
                                com.example.llmapp.core.telemetry.TraceEvent.MemoryCommit(
                                    generationId = sessionId,
                                    memoriesInserted = newMemories.size,
                                    epistemicHash = stateHash
                                )
                            )
                            
                        } catch (e: Exception) {
                            Log.e("CognitiveTaskScheduler", "CRITICAL: Atomic State Transaction Failed!", e)
                            
                            val errorMessage = e.message ?: "Unknown Transaction Failure"
                            replayTracer?.logEvent(
                                com.example.llmapp.core.telemetry.TraceEvent.Error(
                                    generationId = _state.value.activeGenerationId,
                                    errorType = "StateCorruptionError",
                                    message = errorMessage
                                )
                            )
                            
                            emit(CognitiveEvent.RuntimeEvent.Error(
                                CognitiveError.StateCorruptionError("Failed to commit cognitive state: \$errorMessage"),
                                event.generationId
                            ))
                        }
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
}
