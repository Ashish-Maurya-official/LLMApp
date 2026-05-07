package com.example.llmapp.core.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

class LlmInferenceManager(private val context: Context) {
    private var llmInference: LlmInference? = null
    
    private val _outputFlow = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)
    val outputFlow: SharedFlow<Pair<String, Boolean>> = _outputFlow
    
    fun loadModel(modelPath: String, maxTokens: Int = 1024, temperature: Float = 0.8f, topK: Int = 40) {
        llmInference?.close()
        
        val file = File(modelPath)
        if (!file.exists()) {
            throw IllegalArgumentException("Model file not found at $modelPath")
        }

        try {
            // First try with GPU Backend
            val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setPreferredBackend(LlmInference.Backend.GPU)
                .build()
            llmInference = LlmInference.createFromOptions(context, gpuOptions)
            Log.d("LlmInferenceManager", "Loaded model with GPU backend")
        } catch (e: Exception) {
            Log.w("LlmInferenceManager", "GPU backend failed (${e.message}), falling back to CPU")
            // Fallback to CPU backend if OpenCL/Vulkan is broken on this device
            val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            llmInference = LlmInference.createFromOptions(context, cpuOptions)
            Log.d("LlmInferenceManager", "Loaded model with CPU backend")
        }
    }

    fun generateResponseAsync(prompt: String) {
        val inference = llmInference ?: throw IllegalStateException("Model is not loaded")
        
        inference.generateResponseAsync(prompt, ProgressListener { partialResult, done ->
            _outputFlow.tryEmit(partialResult to done)
        })
    }
    
    fun generateResponse(prompt: String): String {
        val inference = llmInference ?: throw IllegalStateException("Model is not loaded")
        return inference.generateResponse(prompt)
    }

    fun close() {
        llmInference?.close()
        llmInference = null
    }
}
