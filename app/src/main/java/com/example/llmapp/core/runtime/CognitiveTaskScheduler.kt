package com.example.llmapp.core.runtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.UUID
import androidx.room.withTransaction

class CognitiveTaskScheduler(private val scope: CoroutineScope) {

    var chatDatabase: com.example.llmapp.core.database.ChatDatabase? = null
    var cognitiveStateDao: com.example.llmapp.core.database.CognitiveStateDao? = null
    var snapshotDao: com.example.llmapp.core.database.SnapshotDao? = null
    var chatDao: com.example.llmapp.core.database.ChatDao? = null
    var replayTracer: com.example.llmapp.core.telemetry.ReplayTracer? = null
    var equilibriumMonitor: com.example.llmapp.core.telemetry.EquilibriumMonitor? = null

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
        scope.launch(Dispatchers.Default) {
            _events.collect { event ->
                processEvent(event)
            }
        }
    }

    fun emit(event: CognitiveEvent) {
        scope.launch(Dispatchers.Default) {
            _events.emit(event)
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
                activeJob = scope.launch(Dispatchers.Default) {
                    telemetry.onConversationReset()
                    telemetry.onGenerationRequested()
                    
                    val routingPath = com.example.llmapp.core.inference.CognitiveLoadBalancer.determineRoutingPath(event.prompt, 0)
                    
                    if (routingPath == com.example.llmapp.core.inference.RoutingPath.STRATEGIC) {
                        Log.d("CognitiveTaskScheduler", "Executing Deep Strategic Path")
                        
                        _state.value = _state.value.copy(phase = ExecutionPhase.PLANNING)
                        emit(CognitiveEvent.RuntimeEvent.TokenEmitted("<thought>Executing Multi-Agent Strategic Graph...\n[Node 1: Planner] Generating execution steps...\n</thought>", false, event.generationId))
                        val plannerPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildPlannerPrompt(event.prompt)
                        val plan = llmInferenceManager?.generateResponse(plannerPrompt) ?: ""
                        
                        _state.value = _state.value.copy(phase = ExecutionPhase.VERIFYING)
                        emit(CognitiveEvent.RuntimeEvent.TokenEmitted("<thought>\n[Node 2: Verifier] Critiquing proposed plan for hallucinations...\n</thought>", false, event.generationId))
                        val verifierPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildVerifierPrompt(event.prompt, plan)
                        val verifiedPlan = llmInferenceManager?.generateResponse(verifierPrompt) ?: ""
                        
                        _state.value = _state.value.copy(phase = ExecutionPhase.SYNTHESIZING)
                        emit(CognitiveEvent.RuntimeEvent.TokenEmitted("<thought>\n[Node 3: Synthesizer] Generating final response...\n</thought>\n\n", false, event.generationId))
                        val synthesizerPrompt = com.example.llmapp.core.inference.ExecutionGraph.buildSynthesizerPrompt(event.prompt, "Retrieved context included in main prompt", verifiedPlan)
                        llmInferenceManager?.generateResponseAsync(synthesizerPrompt, event.generationId)
                        
                    } else {
                        llmInferenceManager?.generateResponseAsync(event.prompt, event.generationId)
                    }
                }
            }
            is CognitiveEvent.ToolEvent.SearchCompleted -> {
                Log.d("CognitiveTaskScheduler", "Tool Search Completed, Resuming LLM")
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
                    llmInferenceManager?.unloadModel()
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
                    Log.e("CognitiveTaskScheduler", "CONSTITUTIONAL VIOLATION: \${e.message}")
                    activeJob?.cancel()
                    _state.value = _state.value.copy(phase = ExecutionPhase.IDLE)
                    emit(CognitiveEvent.RuntimeEvent.Error(
                        CognitiveError.ConstitutionalViolationError(e.message ?: "Unknown Violation"),
                        event.generationId
                    ))
                    return
                }
                
                // Sentinel detection (Web Search Tool)
                val sentinelMatch = SENTINEL_REGEX.find(raw)
                val refusalPhrases = listOf(
                    "I do not have access to the internet", "I cannot access the internet",
                    "I don't have access to the internet", "I cannot browse",
                    "I don't have real-time", "I do not have real-time"
                )
                val needsSearch = sentinelMatch != null || refusalPhrases.any { raw.contains(it, ignoreCase = true) }
                
                if (needsSearch && _state.value.phase != ExecutionPhase.RETRIEVING) {
                    val extractedQuery = sentinelMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: ""
                    Log.d("CognitiveTaskScheduler", "Sentinel detected. Preempting generation for query: $extractedQuery")
                    
                    // Stop LLM immediately
                    activeJob?.cancel()
                    _state.value = _state.value.copy(phase = ExecutionPhase.RETRIEVING)
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
