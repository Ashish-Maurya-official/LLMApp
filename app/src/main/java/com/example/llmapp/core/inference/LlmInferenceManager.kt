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

class LlmInferenceManager(private val context: Context) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    
    private val _outputFlow = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<Pair<String, Boolean>> = _outputFlow
    
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    suspend fun loadModel(modelPath: String, hardwareBackend: String = "Auto", maxTokens: Int = 1024, temperature: Float = 0.8f, topK: Int = 40): String {
        return withContext(Dispatchers.IO) {
            engine?.close()
            
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
                } catch (gpuException: Exception) {
                    Log.w("LlmInferenceManager", "Auto: GPU failed (${gpuException.message}), falling back to CPU")
                    loadWithBackend(modelPath, Backend.CPU(), "CPU")
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

    fun generateResponseAsync(prompt: String) {
        val conv = conversation ?: throw IllegalStateException("Model/Conversation not initialized")
        
        scope.launch {
            try {
                conv.sendMessageAsync(prompt)
                    .onStart { /* Handle start? */ }
                    .onCompletion { _outputFlow.tryEmit("" to true) }
                    .collect { chunk ->
                        _outputFlow.tryEmit(chunk.toString() to false)
                    }
            } catch (e: Exception) {
                _outputFlow.tryEmit("Error: ${e.message}" to true)
            }
        }
    }
    
    fun generateResponse(prompt: String): String = runBlocking {
        val conv = conversation ?: throw IllegalStateException("Model/Conversation not initialized")
        val response = conv.sendMessage(prompt)
        return@runBlocking response.toString()
    }

    fun close() {
        engine?.close()
        engine = null
        conversation = null
        scope.cancel()
    }
}
