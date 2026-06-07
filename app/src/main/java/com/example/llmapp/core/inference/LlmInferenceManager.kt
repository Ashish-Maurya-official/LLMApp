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
    
    // ── GPU/NPU Capability Probe ─────────────────────────────────────────────
    val gpuProbe = GpuCapabilityProbe(context)

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
    
    // FIX(BUG 7): Separate generation jobs for main and orchestrator engines
    // Previously a single `currentGenerationJob` was shared — starting main inference
    // would cancel an active orchestrator job and vice versa.
    private var mainGenerationJob: Job? = null
    private var orchestratorGenerationJob: Job? = null

    // ── Main Model Loading ────────────────────────────────────────────────────
    suspend fun loadMainModel(modelPath: String, hardwareBackend: String = "Auto"): LoadResult {
        return withContext(Dispatchers.IO) {
            inferenceMutex.withLock {
                Log.d(TAG, "[LOAD_MAIN] Requested backend: $hardwareBackend")
                
                // FIX(BUG 4): Close Conversation BEFORE Engine to avoid native resource leaks
                Log.d(TAG, "[LOAD_MAIN] Closing old main conversation...")
                safeCloseConversation(mainConversation, "main")
                mainConversation = null
                Log.d(TAG, "[LOAD_MAIN] Closing old main engine...")
                safeCloseEngine(mainEngine, "main")
                mainEngine = null
                
                // FIX(BUG 5): Use delay() instead of Thread.sleep() — non-blocking
                Log.d(TAG, "[LOAD_MAIN] Forcing GC and waiting for GPU resource release...")
                System.gc()
                delay(1000) // Coroutine-safe delay — doesn't block the IO thread
                
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
                // FIX(BUG 4): Close Conversation BEFORE Engine
                safeCloseConversation(mainConversation, "main")
                mainConversation = null
                safeCloseEngine(mainEngine, "main")
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
                
                // FIX(BUG 4): Close Conversation BEFORE Engine
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Closing old orchestrator conversation...")
                safeCloseConversation(orchestratorConversation, "orchestrator")
                orchestratorConversation = null
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Closing old orchestrator engine...")
                safeCloseEngine(orchestratorEngine, "orchestrator")
                orchestratorEngine = null
                
                // FIX(BUG 5): Use delay() instead of Thread.sleep()
                Log.d(TAG, "[LOAD_ORCHESTRATOR] Forcing GC and waiting for resource release...")
                System.gc()
                delay(500)
                
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
                // FIX(BUG 4): Close Conversation BEFORE Engine
                safeCloseConversation(orchestratorConversation, "orchestrator")
                orchestratorConversation = null
                safeCloseEngine(orchestratorEngine, "orchestrator")
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
     *   GPU → CPU  (if GPU was resolved)
     *   CPU → (no fallback, throw)
     *
     * FIX(BUG 1): Before attempting GPU/NPU, runs the GpuCapabilityProbe to
     * check if the hardware can even support the backend. If the probe fails,
     * skips directly to CPU — preventing the native SIGSEGV that try-catch
     * cannot catch.
     *
     * FIX(BUG 3): Uses crash-history flags to detect if a previous GPU attempt
     * caused a process kill (SIGSEGV). If it did, auto-blocks GPU.
     *
     * Returns Pair(backendName, fallbackError?) — fallbackError is non-null if a fallback occurred.
     */
    private fun loadEngineWithFallback(modelPath: String, preferredBackend: String, cpuThreads: Int = 8, onLoaded: (Engine, Conversation) -> Unit): Pair<String, Throwable?> {
        Log.d(TAG, "[FALLBACK] Starting loadEngineWithFallback: preferred=$preferredBackend, cpuThreads=$cpuThreads")

        return when (preferredBackend) {
            "GPU" -> {
                // FIX(BUG 1 & 3): Pre-flight GPU capability check
                if (!gpuProbe.isGpuSafe()) {
                    val probeError = RuntimeException(
                        "GPU probe BLOCKED initialization. Reason: ${gpuProbe.getDiagnostics()}\n" +
                        "Falling back to CPU to prevent native crash (SIGSEGV)."
                    )
                    Log.w(TAG, "[FALLBACK] GPU BLOCKED by probe — going straight to CPU", probeError)
                    try {
                        loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                        "CPU" to probeError
                    } catch (cpuException: Throwable) {
                        Log.e(TAG, "[FALLBACK] CPU also failed after GPU probe block")
                        val combined = RuntimeException(
                            "GPU blocked by safety probe, CPU also failed: ${cpuException.message}"
                        )
                        combined.addSuppressed(probeError)
                        combined.addSuppressed(cpuException)
                        throw combined
                    }
                } else {
                    // GPU probe passed — attempt GPU with crash detection
                    try {
                        gpuProbe.markGpuInitStarted() // Arm crash detector
                        loadWithBackend(modelPath, Backend.GPU(), "GPU", onLoaded)
                        gpuProbe.markGpuInitSucceeded() // Disarm — init survived
                        "GPU" to null
                    } catch (gpuException: Throwable) {
                        // This catch only works for Java-level exceptions (not SIGSEGV).
                        // If we reach here, it was a "soft" GPU failure (e.g. unsupported ops).
                        // Clear the pending flag — this was NOT a SIGSEGV, so don't count it as a crash.
                        gpuProbe.markGpuInitSucceeded() // Clears pending flag + resets crash counter
                        Log.w(TAG, "[FALLBACK] GPU failed (Java exception: ${gpuException::class.simpleName}), falling back to CPU", gpuException)
                        try {
                            // GC before retry to release any partially-allocated GPU resources
                            System.gc()
                            Thread.sleep(500) // Blocking sleep is OK here — we're already in fallback
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
            }
            "NPU" -> {
                // FIX(BUG 1 & 3): Pre-flight NPU capability check
                if (!gpuProbe.isNpuSafe()) {
                    val probeError = RuntimeException(
                        "NPU probe BLOCKED initialization. NPU safety checks failed (due to previous crashes or low memory).\n" +
                        "Diagnostics: ${gpuProbe.getDiagnostics()}"
                    )
                    Log.w(TAG, "[FALLBACK] NPU BLOCKED by probe — trying GPU next", probeError)
                    // NPU blocked → try GPU → CPU
                    try {
                        if (gpuProbe.isGpuSafe()) {
                            gpuProbe.markGpuInitStarted()
                            loadWithBackend(modelPath, Backend.GPU(), "GPU", onLoaded)
                            gpuProbe.markGpuInitSucceeded()
                            "GPU" to probeError
                        } else {
                            Log.w(TAG, "[FALLBACK] GPU also blocked by probe — falling back to CPU")
                            loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                            "CPU" to probeError
                        }
                    } catch (gpuException: Throwable) {
                        gpuProbe.markGpuInitSucceeded() // Clear pending — not a SIGSEGV
                        Log.w(TAG, "[FALLBACK] GPU also failed after NPU probe block, trying CPU", gpuException)
                        try {
                            System.gc()
                            Thread.sleep(500)
                            loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                            "CPU" to probeError
                        } catch (cpuException: Throwable) {
                            val combined = RuntimeException(
                                "All backends failed. NPU: blocked by probe, GPU: ${gpuException.message}, CPU: ${cpuException.message}"
                            )
                            combined.addSuppressed(probeError)
                            combined.addSuppressed(gpuException)
                            combined.addSuppressed(cpuException)
                            throw combined
                        }
                    }
                } else {
                    try {
                        gpuProbe.markNpuInitStarted()
                        loadWithBackend(modelPath, Backend.NPU(), "NPU", onLoaded)
                        gpuProbe.markNpuInitSucceeded()
                        "NPU" to null
                    } catch (npuException: Throwable) {
                        gpuProbe.markNpuInitSucceeded() // Clear pending — not a SIGSEGV
                        Log.w(TAG, "[FALLBACK] NPU failed (Java exception: ${npuException::class.simpleName}), falling back to GPU", npuException)
                        // GC + delay to release NPU native resources before GPU attempt
                        System.gc()
                        Thread.sleep(500)
                        // NPU failed → try GPU → CPU
                        try {
                            if (gpuProbe.isGpuSafe()) {
                                gpuProbe.markGpuInitStarted()
                                loadWithBackend(modelPath, Backend.GPU(), "GPU", onLoaded)
                                gpuProbe.markGpuInitSucceeded()
                                "GPU" to npuException
                            } else {
                                Log.w(TAG, "[FALLBACK] GPU blocked by probe after NPU failure — falling back to CPU")
                                loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                                "CPU" to npuException
                            }
                        } catch (gpuException: Throwable) {
                            gpuProbe.markGpuInitSucceeded() // Clear pending — not a SIGSEGV
                            Log.w(TAG, "[FALLBACK] GPU also failed after NPU failure, trying CPU", gpuException)
                            try {
                                System.gc()
                                Thread.sleep(500)
                                loadWithBackend(modelPath, Backend.CPU(cpuThreads), "CPU", onLoaded)
                                "CPU" to npuException
                            } catch (cpuException: Throwable) {
                                Log.e(TAG, "[FALLBACK] All backends (NPU, GPU, CPU) failed for $modelPath")
                                val combined = RuntimeException(
                                    "All backends failed. NPU: ${npuException.message}, GPU: ${gpuException.message}, CPU: ${cpuException.message}"
                                )
                                combined.addSuppressed(npuException)
                                combined.addSuppressed(gpuException)
                                combined.addSuppressed(cpuException)
                                throw combined
                            }
                        }
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
        try {
            Log.d(TAG, "[LOAD_BACKEND] Calling Engine.initialize() ($backendName)... this is where native crashes can occur")
            newEngine.initialize()
            Log.d(TAG, "[LOAD_BACKEND] Engine initialized! Creating Conversation ($backendName)...")
            onLoaded(newEngine, newEngine.createConversation())
            Log.d(TAG, "[LOAD_BACKEND] ✅ Successfully loaded with $backendName")
            return backendName
        } catch (e: Throwable) {
            // Close the engine to release native resources before re-throwing.
            // Without this, the leaked native Engine causes SIGSEGV on the next backend attempt.
            Log.w(TAG, "[LOAD_BACKEND] ❌ $backendName failed, closing engine to release native resources")
            try { newEngine.close() } catch (closeErr: Throwable) {
                Log.w(TAG, "[LOAD_BACKEND] Error closing failed $backendName engine (non-fatal): ${closeErr.message}")
            }
            throw e
        }
    }

    // ── Safe resource cleanup helpers ────────────────────────────────────────
    // FIX(BUG 4): Always close Conversation before Engine, wrapped in try-catch
    // to prevent cascading failures if one close() throws.
    
    private fun safeCloseConversation(conversation: Conversation?, label: String) {
        try {
            conversation?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing $label conversation (non-fatal): ${e.message}")
        }
    }

    private fun safeCloseEngine(engine: Engine?, label: String) {
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing $label engine (non-fatal): ${e.message}")
        }
    }

    fun stopGeneration() {
        mainGenerationJob?.cancel()
        orchestratorGenerationJob?.cancel()
        Log.d("LlmInferenceManager", "Generation stopped by user/system.")
    }

    // ── Orchestrator Inference (Synchronous → Suspend) ───────────────────────
    // FIX(BUG 6): Changed from `runBlocking` to `suspend fun`.
    // `runBlocking` was blocking the 4-thread agentDispatcher pool in
    // CognitiveTaskScheduler, risking deadlock during long inference calls.
    suspend fun generateOrchestratorResponse(prompt: String): String {
        return orchestratorMutex.withLock {
            val eng = orchestratorEngine ?: throw java.lang.IllegalStateException("Orchestrator Engine not loaded")
            // Always reset conversation for orchestrator to avoid context creep
            safeCloseConversation(orchestratorConversation, "orchestrator")
            val freshConv = eng.createConversation()
            orchestratorConversation = freshConv
            
            // Run the blocking inference call on the dedicated orchestrator thread
            withContext(orchestratorDispatcher) {
                freshConv.sendMessage(prompt).toString()
            }
        }
    }

    // FIX(BUG 7): Uses separate `orchestratorGenerationJob` instead of shared `currentGenerationJob`
    fun generateOrchestratorResponseAsync(prompt: String, generationId: String) {
        val oldJob = orchestratorGenerationJob
        orchestratorGenerationJob = scope.launch(orchestratorDispatcher) {
            try { oldJob?.cancelAndJoin() } catch (e: Exception) {}
            
            val freshConv = orchestratorMutex.withLock {
                delay(200)
                val eng = orchestratorEngine
                if (eng != null) {
                    safeCloseConversation(orchestratorConversation, "orchestrator")
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
    // FIX(BUG 7): Uses separate `mainGenerationJob` instead of shared `currentGenerationJob`
    fun generateMainResponseAsync(prompt: String, generationId: String) {
        val oldJob = mainGenerationJob
        mainGenerationJob = scope.launch(mainEngineDispatcher) {
            try { oldJob?.cancelAndJoin() } catch (e: Exception) {}
            
            val freshConv = inferenceMutex.withLock {
                delay(200)
                val eng = mainEngine
                if (eng != null) {
                    safeCloseConversation(mainConversation, "main")
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

    // FIX(BUG 4): Correct resource cleanup order — Conversation before Engine
    fun close() {
        safeCloseConversation(mainConversation, "main")
        mainConversation = null
        safeCloseEngine(mainEngine, "main")
        mainEngine = null
        
        safeCloseConversation(orchestratorConversation, "orchestrator")
        orchestratorConversation = null
        safeCloseEngine(orchestratorEngine, "orchestrator")
        orchestratorEngine = null
        
        scope.cancel()
    }
}
