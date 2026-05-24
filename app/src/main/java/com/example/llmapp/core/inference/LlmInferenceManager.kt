package com.example.llmapp.core.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LlmInferenceManager(private val context: Context) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    
    private val _outputFlow = MutableSharedFlow<Triple<String, Boolean, String>>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<Triple<String, Boolean, String>> = _outputFlow
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val inferenceMutex = Mutex()
    private var currentGenerationJob: Job? = null

    suspend fun loadModel(modelPath: String, hardwareBackend: String = "Auto", maxTokens: Int = 1024, temperature: Float = 0.8f, topK: Int = 40): String {
        return withContext(Dispatchers.IO) {
            inferenceMutex.withLock {
                conversation?.close()
                conversation = null
                engine?.close()
                engine = null
                
                // Explicitly ask JVM to clean up native refs to free RAM before allocating 2GB again
                System.gc()
                Thread.sleep(200) // Small pause for GC to finalize
                
                val file = File(modelPath)
                if (!file.exists()) {
                    throw IllegalArgumentException("Model file not found at $modelPath")
                }

                if (hardwareBackend == "CPU") {
                    loadWithBackend(modelPath, Backend.CPU(), "CPU")
                } else if (hardwareBackend == "GPU") {
                    loadWithBackend(modelPath, Backend.GPU(), "GPU")
                } else {
                    // Auto: Try GPU, fallback to CPU
                    try {
                        loadWithBackend(modelPath, Backend.GPU(), "GPU")
                    } catch (gpuException: Throwable) {
                        Log.w("LlmInferenceManager", "Auto: GPU failed (${gpuException.message}), falling back to CPU")
                        loadWithBackend(modelPath, Backend.CPU(), "CPU")
                    }
                }
            }
        }
    }

    private fun loadWithBackend(modelPath: String, backendConfig: Backend, backendName: String): String {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backendConfig
        )
        val newEngine = Engine(config)
        newEngine.initialize() // This is the heavy part
        
        engine = newEngine
        conversation = newEngine.createConversation()
        Log.d("LlmInferenceManager", "Loaded LiteRT-LM model successfully with $backendName")
        return backendName
    }

    suspend fun unloadModel() {
        inferenceMutex.withLock {
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
            Log.w("LlmInferenceManager", "Engine completely unloaded for thermal/memory recovery.")
        }
    }

    /**
     * Resets the Conversation's internal KV-cache by creating a fresh Conversation
     * from the existing Engine. This MUST be called before every generation when
     * using a full-history reconstructed prompt, otherwise the KV-cache doubles the
     * conversation every turn, overflowing the context window after ~5 messages.
     */
    fun resetConversation() {
        val eng = engine ?: return
        conversation?.close()
        conversation = eng.createConversation()
    }

    fun stopGeneration() {
        currentGenerationJob?.cancel()
        Log.d("LlmInferenceManager", "Generation stopped by user.")
    }

    fun generateResponseAsync(prompt: String, generationId: String) {
        val oldJob = currentGenerationJob
        currentGenerationJob = scope.launch {
            try {
                oldJob?.cancelAndJoin()
            } catch (e: Exception) {
                Log.w("LlmInferenceManager", "Error joining previous generation job: ${e.message}")
            }
            val freshConv = inferenceMutex.withLock {
                // Ensure native threads are completely halted before closing native conversation refs
                delay(200)
                resetConversation()
                conversation
            }
            
            if (freshConv == null) {
                _outputFlow.emit(Triple("Error: Model not initialized", true, generationId))
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
                android.util.Log.e("LlmInferenceManager", "Generation error", t)
                if (!completionEmitted) {
                    completionEmitted = true
                    _outputFlow.emit(Triple("Error: ${t.message}", true, generationId))
                }
            }
        }
    }
    
    fun generateResponse(prompt: String): String = runBlocking {
        inferenceMutex.withLock {
            val conv = conversation ?: throw IllegalStateException("Model/Conversation not initialized")
            val response = conv.sendMessage(prompt)
            return@runBlocking response.toString()
        }
    }

    fun close() {
        engine?.close()
        engine = null
        conversation = null
        scope.cancel()
    }
}
