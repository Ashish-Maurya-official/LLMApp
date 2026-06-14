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
        fun resolveBackendPreference(preferred: String): String {
            return when (preferred) {
                "Auto" -> "GPU"
                else -> preferred
            }
        }
    }
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
    
    // ── FunctionGemma Router Engine ────────────────────────────────────────────
    private var routerEngine: Engine? = null
    private var routerConversation: Conversation? = null
    val isRouterLoaded: Boolean
        get() = routerEngine != null
    var activeRouterBackend: String? = null
        private set

    private val _outputFlow = MutableSharedFlow<Triple<String, Boolean, String>>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<Triple<String, Boolean, String>> = _outputFlow
    
    // Single-thread dispatchers to avoid CPU contention between the engines
    private val routerDispatcher = Executors.newSingleThreadExecutor { Thread(it, "Router-Thread") }.asCoroutineDispatcher()
    private val mainEngineDispatcher = Executors.newSingleThreadExecutor { Thread(it, "MainEngine-Thread") }.asCoroutineDispatcher()

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val inferenceMutex = Mutex()
    private val routerMutex = Mutex()
    
    // Separate generation jobs for main and router engines
    private var mainGenerationJob: Job? = null
    private var routerGenerationJob: Job? = null

    // ── Main Model Loading ────────────────────────────────────────────────────
    suspend fun loadMainModel(modelPath: String, hardwareBackend: String = "Auto"): LoadResult {
        return withContext(Dispatchers.IO) {
            inferenceMutex.withLock {
                Log.d(TAG, "[LOAD_MAIN] Requested backend: $hardwareBackend")
                
                // Close conversation before engine to prevent native resource leaks
                Log.d(TAG, "[LOAD_MAIN] Closing old main conversation...")
                safeCloseConversation(mainConversation, "main")
                mainConversation = null
                Log.d(TAG, "[LOAD_MAIN] Closing old main engine...")
                safeCloseEngine(mainEngine, "main")
                mainEngine = null
                
                Log.d(TAG, "[LOAD_MAIN] Forcing GC and waiting for GPU resource release...")
                System.gc()
                delay(1000)
                
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

    // ── Router Model Loading (FunctionGemma 270M, CPU typically) ──────────────
    suspend fun loadRouterModel(modelPath: String, hardwareBackend: String = "CPU"): LoadResult {
        return withContext(Dispatchers.IO) {
            routerMutex.withLock {
                Log.d(TAG, "[LOAD_ROUTER] Requested backend: $hardwareBackend")
                
                Log.d(TAG, "[LOAD_ROUTER] Closing old router conversation...")
                safeCloseConversation(routerConversation, "router")
                routerConversation = null
                Log.d(TAG, "[LOAD_ROUTER] Closing old router engine...")
                safeCloseEngine(routerEngine, "router")
                routerEngine = null
                
                Log.d(TAG, "[LOAD_ROUTER] Forcing GC and waiting for resource release...")
                System.gc()
                delay(500)
                
                Log.d(TAG, "[LOAD_ROUTER] Checking file existence for $modelPath...")
                val file = File(modelPath)
                if (!file.exists()) throw IllegalArgumentException("Model file not found at $modelPath")

                val resolvedBackend = resolveBackendPreference(hardwareBackend)
                Log.d(TAG, "[LOAD_ROUTER] Resolved backend: $hardwareBackend → $resolvedBackend")

                val (backendName, fallbackError) = loadEngineWithFallback(modelPath, resolvedBackend, 4) { eng, conv ->
                    routerEngine = eng
                    routerConversation = conv
                }
                activeRouterBackend = backendName
                Log.d(TAG, "[LOAD_ROUTER] ✅ Loaded ROUTER model successfully with $backendName")
                LoadResult(backendName, fallbackError)
            }
        }
    }

    suspend fun unloadRouterModel() {
        withContext(Dispatchers.IO) {
            routerMutex.withLock {
                safeCloseConversation(routerConversation, "router")
                routerConversation = null
                safeCloseEngine(routerEngine, "router")
                routerEngine = null
                activeRouterBackend = null
                System.gc()
                Log.d(TAG, "Unloaded ROUTER model successfully")
            }
        }
    }

    private fun loadEngineWithFallback(modelPath: String, preferredBackend: String, cpuThreads: Int = 8, onLoaded: (Engine, Conversation) -> Unit): Pair<String, Throwable?> {
        Log.d(TAG, "[FALLBACK] Starting loadEngineWithFallback: preferred=$preferredBackend, cpuThreads=$cpuThreads")

        return when (preferredBackend) {
            "GPU" -> {
                // Guard GPU load with safety probe
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
                    try {
                        gpuProbe.markGpuInitStarted() // Arm crash detector
                        loadWithBackend(modelPath, Backend.GPU(), "GPU", onLoaded)
                        gpuProbe.markGpuInitSucceeded() // Disarm — init survived
                        "GPU" to null
                    } catch (gpuException: Throwable) {
                        gpuProbe.markGpuInitSucceeded()
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
                // Guard NPU load with safety probe
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
            // Close the engine to release native resources and avoid leaking references
            Log.w(TAG, "[LOAD_BACKEND] ❌ $backendName failed, closing engine to release native resources")
            try { newEngine.close() } catch (closeErr: Throwable) {
                Log.w(TAG, "[LOAD_BACKEND] Error closing failed $backendName engine (non-fatal): ${closeErr.message}")
            }
            throw e
        }
    }
    
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
        routerGenerationJob?.cancel()
        Log.d(TAG, "Generation stopped by user/system.")
    }

    suspend fun generateRouterResponse(prompt: String): String {
        return routerMutex.withLock {
            val eng = routerEngine ?: throw IllegalStateException("Router Engine not loaded")
            safeCloseConversation(routerConversation, "router")
            val freshConv = eng.createConversation()
            routerConversation = freshConv
            
            withContext(routerDispatcher) {
                freshConv.sendMessage(prompt).toString()
            }
        }
    }


    fun generateRouterResponseAsync(prompt: String, generationId: String) {
        val oldJob = routerGenerationJob
        routerGenerationJob = scope.launch(routerDispatcher) {
            try { oldJob?.cancelAndJoin() } catch (e: Exception) {}
            
            val freshConv = routerMutex.withLock {
                delay(200)
                val eng = routerEngine
                if (eng != null) {
                    safeCloseConversation(routerConversation, "router")
                    routerConversation = eng.createConversation()
                }
                routerConversation
            }
            
            if (freshConv == null) {
                _outputFlow.emit(Triple("Error: Router Model not initialized", true, generationId))
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
                Log.e(TAG, "Router Async Generation error", t)
                if (!completionEmitted) {
                    completionEmitted = true
                    _outputFlow.emit(Triple("Error: ${t.message}", true, generationId))
                }
            }
        }
    }

    // ── Main Inference (Streaming Async) ─────────────────────────────────────
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

    /**
     * Suspends until the main model's current generation job completes.
     */
    suspend fun awaitMainGenerationComplete() {
        mainGenerationJob?.join()
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
        safeCloseConversation(mainConversation, "main")
        mainConversation = null
        safeCloseEngine(mainEngine, "main")
        mainEngine = null
        
        safeCloseConversation(routerConversation, "router")
        routerConversation = null
        safeCloseEngine(routerEngine, "router")
        routerEngine = null
        
        scope.cancel()
    }
}
