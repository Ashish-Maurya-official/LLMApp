package com.example.llmapp.core.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onCompletion
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

import com.example.llmapp.core.settings.SettingsManager

class LlmInferenceManager(private val context: Context, private val settingsManager: SettingsManager) {
    
    // ── Main Reasoning Engine (Level 2) ──────────────────────────────────────
    private var mainEngine: Engine? = null
    private var mainConversation: Conversation? = null
    val isMainModelLoaded: Boolean
        get() = mainEngine != null
    var activeMainBackend: String? = null
        private set
    
    // ── Cognitive Orchestrator Engine (Level 1) ──────────────────────────────
    private var orchestratorEngine: Engine? = null
    private var orchestratorConversation: Conversation? = null
    val isOrchestratorLoaded: Boolean
        get() = orchestratorEngine != null
    var activeOrchestratorBackend: String? = null
        private set

    private val _outputFlow = MutableSharedFlow<Triple<String, Boolean, String>>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<Triple<String, Boolean, String>> = _outputFlow
    
    // Dedicated Single-Thread Executors for each engine to prevent CPU contention and UI jank
    private val orchestratorDispatcher = Executors.newSingleThreadExecutor { Thread(it, "Orchestrator-Thread") }.asCoroutineDispatcher()
    private val mainEngineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "MainEngine-Thread") }.asCoroutineDispatcher()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val inferenceMutex = Mutex()
    private val orchestratorMutex = Mutex()
    
    private var currentGenerationJob: Job? = null

    // ── Main Model Loading (GPU typically) ───────────────────────────────────
    suspend fun loadMainModel(modelPath: String, hardwareBackend: String = "Auto"): String {
        return withContext(Dispatchers.IO) {
            inferenceMutex.withLock {
                Log.d("LlmInferenceManager", "[LOAD_MAIN] Closing old main conversation...")
                mainConversation?.close()
                mainConversation = null
                Log.d("LlmInferenceManager", "[LOAD_MAIN] Closing old main engine...")
                mainEngine?.close()
                mainEngine = null
                
                Log.d("LlmInferenceManager", "[LOAD_MAIN] Forcing GC...")
                System.gc()
                Thread.sleep(200)
                
                Log.d("LlmInferenceManager", "[LOAD_MAIN] Checking file existence for $modelPath...")
                val file = File(modelPath)
                if (!file.exists()) throw IllegalArgumentException("Model file not found at $modelPath")

                Log.d("LlmInferenceManager", "[LOAD_MAIN] Calling loadEngineWithFallback...")
                val backendName = loadEngineWithFallback(modelPath, hardwareBackend, 8) { eng, conv ->
                    mainEngine = eng
                    mainConversation = conv
                }
                activeMainBackend = backendName
                Log.d("LlmInferenceManager", "Loaded MAIN model successfully with $backendName")
                backendName
            }
        }
    }

    suspend fun unloadMainModel() {
        withContext(Dispatchers.IO) {
            inferenceMutex.withLock {
                mainConversation?.close()
                mainConversation = null
                mainEngine?.close()
                mainEngine = null
                activeMainBackend = null
                System.gc()
                Log.d("LlmInferenceManager", "Unloaded MAIN model successfully")
            }
        }
    }

    // ── Orchestrator Model Loading (CPU typically) ───────────────────────────
    suspend fun loadOrchestratorModel(modelPath: String, hardwareBackend: String = "CPU"): String {
        return withContext(Dispatchers.IO) {
            orchestratorMutex.withLock {
                Log.d("LlmInferenceManager", "[LOAD_ORCHESTRATOR] Closing old orchestrator conversation...")
                orchestratorConversation?.close()
                orchestratorConversation = null
                Log.d("LlmInferenceManager", "[LOAD_ORCHESTRATOR] Closing old orchestrator engine...")
                orchestratorEngine?.close()
                orchestratorEngine = null
                
                Log.d("LlmInferenceManager", "[LOAD_ORCHESTRATOR] Forcing GC...")
                System.gc()
                Thread.sleep(200)
                
                Log.d("LlmInferenceManager", "[LOAD_ORCHESTRATOR] Checking file existence for $modelPath...")
                val file = File(modelPath)
                if (!file.exists()) throw IllegalArgumentException("Model file not found at $modelPath")

                Log.d("LlmInferenceManager", "[LOAD_ORCHESTRATOR] Calling loadEngineWithFallback...")
                val backendName = loadEngineWithFallback(modelPath, hardwareBackend, 4) { eng, conv ->
                    orchestratorEngine = eng
                    orchestratorConversation = conv
                }
                activeOrchestratorBackend = backendName
                Log.d("LlmInferenceManager", "Loaded ORCHESTRATOR model successfully with $backendName")
                backendName
            }
        }
    }

    suspend fun unloadOrchestratorModel() {
        withContext(Dispatchers.IO) {
            orchestratorMutex.withLock {
                orchestratorConversation?.close()
                orchestratorConversation = null
                orchestratorEngine?.close()
                orchestratorEngine = null
                activeOrchestratorBackend = null
                System.gc()
                Log.d("LlmInferenceManager", "Unloaded ORCHESTRATOR model successfully")
            }
        }
    }

    private fun loadEngineWithFallback(modelPath: String, preferredBackend: String, cpuThreads: Int = 8, onLoaded: (Engine, Conversation) -> Unit): String {
        val tryGpuFirst = preferredBackend == "GPU" || preferredBackend == "Auto"
        
        return if (tryGpuFirst) {
            try {
                loadWithBackend(modelPath, Backend.GPU(), "GPU", onLoaded)
            } catch (gpuException: Throwable) {
                Log.w("LlmInferenceManager", "GPU failed or unavailable, falling back to CPU for $modelPath", gpuException)
                try {
                    loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                } catch (cpuException: Throwable) {
                    Log.e("LlmInferenceManager", "Both GPU and CPU failed for $modelPath")
                    throw gpuException
                }
            }
        } else {
            try {
                loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
            } catch (cpuException: Throwable) {
                Log.w("LlmInferenceManager", "CPU failed, falling back to GPU for $modelPath", cpuException)
                try {
                    loadWithBackend(modelPath, Backend.GPU(), "GPU", onLoaded)
                } catch (gpuException: Throwable) {
                    Log.e("LlmInferenceManager", "Both CPU and GPU failed for $modelPath")
                    throw cpuException
                }
            }
        }
    }

    private fun loadWithBackend(modelPath: String, backendConfig: Backend, backendName: String, onLoaded: (Engine, Conversation) -> Unit): String {
        Log.d("LlmInferenceManager", "[LOAD_BACKEND] Creating EngineConfig for $backendName...")
        val config = EngineConfig(
            modelPath = modelPath, 
            backend = backendConfig
        )
        Log.d("LlmInferenceManager", "[LOAD_BACKEND] Creating Engine instance...")
        val newEngine = Engine(config)
        Log.d("LlmInferenceManager", "[LOAD_BACKEND] Initializing Engine instance natively...")
        newEngine.initialize()
        Log.d("LlmInferenceManager", "[LOAD_BACKEND] Creating Conversation instance...")
        onLoaded(newEngine, newEngine.createConversation())
        Log.d("LlmInferenceManager", "[LOAD_BACKEND] Successfully loaded with $backendName")
        return backendName
    }


    fun stopGeneration() {
        currentGenerationJob?.cancel()
        Log.d("LlmInferenceManager", "Generation stopped by user/system.")
    }

    // ── Orchestrator Inference (Synchronous) ─────────────────────────────────
    fun generateOrchestratorResponse(prompt: String): String = runBlocking {
        orchestratorMutex.withLock {
            val eng = orchestratorEngine ?: throw java.lang.IllegalStateException("Orchestrator Engine not loaded")
            // Always reset conversation for orchestrator to avoid context creep
            orchestratorConversation?.close()
            val freshConv = eng.createConversation()
            orchestratorConversation = freshConv
            
            return@runBlocking freshConv.sendMessage(prompt).toString()
        }
    }

    fun generateOrchestratorResponseAsync(prompt: String, generationId: String) {
        val oldJob = currentGenerationJob
        currentGenerationJob = scope.launch(orchestratorDispatcher) {
            try { oldJob?.cancelAndJoin() } catch (e: Exception) {}
            
            val freshConv = orchestratorMutex.withLock {
                delay(200)
                val eng = orchestratorEngine
                if (eng != null) {
                    orchestratorConversation?.close()
                    orchestratorConversation = eng.createConversation()
                }
                orchestratorConversation
            }
            
            if (freshConv == null) {
                _outputFlow.emit(Triple("Error: Orchestrator Model not initialized", true, generationId))
                return@launch
            }

            var completionEmitted = false
            try {
                freshConv.sendMessageAsync(prompt)
                    .onCompletion {
                        if (!completionEmitted) {
                            completionEmitted = true
                            _outputFlow.emit(Triple("", true, generationId))
                        }
                    }
                    .collect { chunk ->
                        _outputFlow.emit(Triple(chunk.toString(), false, generationId))
                    }
            } catch (t: Throwable) {
                android.util.Log.e("LlmInferenceManager", "Orchestrator Async Generation error", t)
                if (!completionEmitted) {
                    completionEmitted = true
                    _outputFlow.emit(Triple("Error: ${t.message}", true, generationId))
                }
            }
        }
    }

    // ── Main Inference (Streaming Async) ─────────────────────────────────────
    fun generateMainResponseAsync(prompt: String, generationId: String) {
        val oldJob = currentGenerationJob
        currentGenerationJob = scope.launch(mainEngineDispatcher) {
            try { oldJob?.cancelAndJoin() } catch (e: Exception) {}
            
            val freshConv = inferenceMutex.withLock {
                delay(200)
                val eng = mainEngine
                if (eng != null) {
                    mainConversation?.close()
                    mainConversation = eng.createConversation()
                }
                mainConversation
            }
            
            if (freshConv == null) {
                _outputFlow.emit(Triple("Error: Main Model not initialized", true, generationId))
                return@launch
            }

            var completionEmitted = false
            try {
                freshConv.sendMessageAsync(prompt)
                    .onCompletion {
                        if (!completionEmitted) {
                            completionEmitted = true
                            _outputFlow.emit(Triple("", true, generationId))
                        }
                    }
                    .collect { chunk ->
                        _outputFlow.emit(Triple(chunk.toString(), false, generationId))
                    }
            } catch (t: Throwable) {
                android.util.Log.e("LlmInferenceManager", "Main Generation error", t)
                if (!completionEmitted) {
                    completionEmitted = true
                    _outputFlow.emit(Triple("Error: ${t.message}", true, generationId))
                }
            }
        }
    }
    
    // Fallback legacy method (points to main engine)
    fun loadModel(modelPath: String, hardwareBackend: String = "Auto"): String = runBlocking {
        loadMainModel(modelPath, hardwareBackend)
    }
    fun generateResponse(prompt: String): String = runBlocking {
        inferenceMutex.withLock {
            val conv = mainConversation ?: throw IllegalStateException("Model not initialized")
            conv.sendMessage(prompt).toString()
        }
    }

    fun close() {
        mainEngine?.close()
        mainEngine = null
        mainConversation = null
        
        orchestratorEngine?.close()
        orchestratorEngine = null
        orchestratorConversation = null
        
        scope.cancel()
    }
}
