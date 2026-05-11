package com.example.llmapp.core.regulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Represents a conversation thread that was interrupted before it could finish.
 */
data class SuspendedIntent(
    val generationId: String,
    val originalPrompt: String,
    val partialResponse: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages cognitive intent threads. If the user interrupts the AI (barge-in),
 * the active thought process is saved here so it can be resumed later.
 */
class IntentThreadManager {

    private val _suspendedQueue = MutableStateFlow<List<SuspendedIntent>>(emptyList())
    val suspendedQueue: StateFlow<List<SuspendedIntent>> = _suspendedQueue.asStateFlow()

    fun suspendIntent(generationId: String, prompt: String, partialResponse: String) {
        // Don't suspend empty intents
        if (prompt.isBlank() && partialResponse.isBlank()) return
        
        _suspendedQueue.update { current ->
            val intent = SuspendedIntent(generationId, prompt, partialResponse)
            val updated = current.toMutableList()
            updated.add(intent)
            
            // Keep maximum of 5 suspended intents to prevent infinite memory bloat
            if (updated.size > 5) {
                updated.removeAt(0)
            }
            updated
        }
    }

    fun popNextIntent(): SuspendedIntent? {
        var next: SuspendedIntent? = null
        _suspendedQueue.update { current ->
            if (current.isEmpty()) return@update current
            
            val updated = current.toMutableList()
            next = updated.removeLast() // Resume most recent first (LIFO)
            updated
        }
        return next
    }
    
    fun clearQueue() {
        _suspendedQueue.value = emptyList()
    }
}
