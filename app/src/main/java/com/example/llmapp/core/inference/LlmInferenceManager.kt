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

    companion object {
        private const val TAG = "LlmInferenceManager"

        /**
         * Resolves the effective backend for a given preference.
         * No hardcoded device blocking — the loadEngineWithFallback chain
         * handles any initialization failures gracefully via exception → CPU fallback.
         *
         * "Auto" → "GPU" (fallback chain will try GPU → CPU)
         * "GPU"  → "GPU" (fallback chain will try GPU → CPU)
         * "NPU"  → "NPU" (fallback chain will try NPU → CPU)
         * "CPU"  → "CPU" (no fallback needed)
         */
        fun resolveBackendPreference(preferred: String): String {
            return when (preferred) {
                "Auto" -> "GPU" // Let the fallback chain try GPU first, then CPU
                else -> preferred // Pass through: GPU, NPU, CPU — all handled by fallback chain
            }
        }
    }

    /** Result of a model load attempt, carrying the actual backend and any fallback error. */
    data class LoadResult(
        val backendName: String,
        val fallbackError: Throwable? = null
    ) {
        /** True if a fallback occurred (requested backend != loaded backend). */
        val didFallback: Boolean get() = fallbackError != null

        /** The full error stacktrace as a string, for the expandable error view. */
        val errorDetails: String? get() = fallbackError?.let { throwable ->
            buildString {
                appendLine(throwable::class.qualifiedName ?: throwable::class.simpleName)
                appendLine(throwable.message ?: "No message")
                appendLine()
                throwable.stackTrace.take(15).forEach { frame ->
                    appendLine("  at $frame")
                }
                if (throwable.stackTrace.size > 15) appendLine("  ... (${throwable.stackTrace.size - 15} more frames)")
            }
        }
    }
    
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

    // ── Main Model Loading ────────────────────────────────────────────────────
    suspend fun loadMainModel(modelPath: String, hardwareBackend: String = "Auto"): LoadResult {
        return withContext(Dispatchers.IO) {
            inferenceMutex.withLock {
                Log.d(TAG, "[LOAD_MAIN] Requested backend: $hardwareBackend")
                Log.d(TAG, "[LOAD_MAIN] Closing old main conversation...")
                mainConversation?.close()
                mainConversation = null
                Log.d(TAG, "[LOAD_MAIN] Closing old main engine...")
                mainEngine?.close()
                mainEngine = null
                
                Log.d(TAG, "[LOAD_MAIN] Forcing GC and waiting for GPU resource release...")
                System.gc()
                Thread.sleep(1000) // GPU driver needs time to fully release VRAM
                
                Log.d(TAG, "[LOAD_MAIN] Checking file existence for $modelPath...")
                val file = File(modelPath)
                if (!file.exists()) throw IllegalArgumentException("Model file not found at $modelPath")

                // Resolve the preferred backend against device capabilities
                val resolvedBackend = resolveBackendPreference(hardwareBackend)
                Log.d(TAG, "[LOAD_MAIN] Resolved backend: $hardwareBackend → $resolvedBackend")

                val (backendName, fallbackError) = loadEngineWithFallback(modelPath, resolvedBackend, 8) { eng, conv ->
                    mainEngine = eng
                    mainConversation = conv
                }
                activeMainBackend = backendName
                Log.d(TAG, "[LOAD_MAIN] ✅ Loaded MAIN model successfully with $backendName")
                LoadResult(backendName, fallbackError)
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
    suspend fun loadOrchestratorModel(modelPath: String, hardwareBackend: String = "CPU"): LoadResult {
        return withContext(Dispatchers.IO) {
            orchestratorMutex.withLock {
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Requested backend: $hardwareBackend")
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Closing old orchestrator conversation...")
                orchestratorConversation?.close()
                orchestratorConversation = null
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Closing old orchestrator engine...")
                orchestratorEngine?.close()
                orchestratorEngine = null
                
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Forcing GC and waiting for resource release...")
                System.gc()
                Thread.sleep(500)
                
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Checking file existence for $modelPath...")
                val file = File(modelPath)
                if (!file.exists()) throw IllegalArgumentException("Model file not found at $modelPath")

                val resolvedBackend = resolveBackendPreference(hardwareBackend)
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Resolved backend: $hardwareBackend → $resolvedBackend")

                val (backendName, fallbackError) = loadEngineWithFallback(modelPath, resolvedBackend, 4) { eng, conv ->
                    orchestratorEngine = eng
                    orchestratorConversation = conv
                }
                activeOrchestratorBackend = backendName
                Log.d(TAG, "[LOAD_ORCHESTRATOR] ✅ Loaded ORCHESTRATOR model successfully with $backendName")
                LoadResult(backendName, fallbackError)
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

    /**
     * Loads the engine with the resolved backend, with a robust fallback chain:
     *   NPU → CPU  (if NPU was resolved)
     *   GPU → CPU  (if GPU was resolved — rare after pre-flight)
     *   CPU → (no fallback, throw)
     *
     * Returns Pair(backendName, fallbackError?) — fallbackError is non-null if a fallback occurred.
     */
    private fun loadEngineWithFallback(modelPath: String, preferredBackend: String, cpuThreads: Int = 8, onLoaded: (Engine, Conversation) -> Unit): Pair<String, Throwable?> {
        Log.d(TAG, "[FALLBACK] Starting loadEngineWithFallback: preferred=$preferredBackend, cpuThreads=$cpuThreads")

        return when (preferredBackend) {
            "GPU" -> {
                // Pre-flight already passed (resolveBackendPreference only emits "GPU" if safe)
                try {
                    loadWithBackend(modelPath, Backend.GPU(), "GPU", onLoaded)
                    "GPU" to null
                } catch (gpuException: Throwable) {
                    Log.w(TAG, "[FALLBACK] GPU failed, falling back to CPU", gpuException)
                    try {
                        // GC before retry to release any partially-allocated GPU resources
                        System.gc()
                        Thread.sleep(500)
                        loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                        "CPU" to gpuException
                    } catch (cpuException: Throwable) {
                        Log.e(TAG, "[FALLBACK] Both GPU and CPU failed for $modelPath")
                        val combined = RuntimeException(
                            "All backends failed. GPU: ${gpuException.message}, CPU: ${cpuException.message}"
                        )
                        combined.addSuppressed(gpuException)
                        combined.addSuppressed(cpuException)
                        throw combined
                    }
                }
            }
            "NPU" -> {
                try {
                    loadWithBackend(modelPath, Backend.NPU(), "NPU", onLoaded)
                    "NPU" to null
                } catch (npuException: Throwable) {
                    Log.w(TAG, "[FALLBACK] NPU failed, falling back to CPU", npuException)
                    try {
                        loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                        "CPU" to npuException
                    } catch (cpuException: Throwable) {
                        Log.e(TAG, "[FALLBACK] Both NPU and CPU failed for $modelPath")
                        val combined = RuntimeException(
                            "All backends failed. NPU: ${npuException.message}, CPU: ${cpuException.message}"
                        )
                        combined.addSuppressed(npuException)
                        combined.addSuppressed(cpuException)
                        throw combined
                    }
                }
            }
            else -> {
                // "CPU" or any unknown → CPU directly, no fallback needed
                loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                "CPU" to null
            }
        }
    }

    private fun loadWithBackend(modelPath: String, backendConfig: Backend, backendName: String, onLoaded: (Engine, Conversation) -> Unit): String {
        Log.d(TAG, "[LOAD_BACKEND] ── Attempting $backendName ──")
        Log.d(TAG, "[LOAD_BACKEND] Creating EngineConfig for $backendName...")
        val config = EngineConfig(
            modelPath = modelPath, 
            backend = backendConfig
        )
        Log.d(TAG, "[LOAD_BACKEND] Creating Engine instance ($backendName)...")
        val newEngine = Engine(config)
        Log.d(TAG, "[LOAD_BACKEND] Calling Engine.initialize() ($backendName)... this is where native crashes can occur")
        newEngine.initialize()
        Log.d(TAG, "[LOAD_BACKEND] Engine initialized! Creating Conversation ($backendName)...")
        onLoaded(newEngine, newEngine.createConversation())
        Log.d(TAG, "[LOAD_BACKEND] ✅ Successfully loaded with $backendName")
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
        loadMainModel(modelPath, hardwareBackend).backendName
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
