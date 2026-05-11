package com.example.llmapp.core.identity

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Monitors the semantic entropy (topic drift) of the active conversation.
 * If the conversation jumps between too many topics too quickly, LLMs suffer
 * from "Context Collapse" where they lose track of the core narrative.
 */
class ContextEntropyMonitor {

    private val topicWindow = mutableListOf<String>()
    private val ENTROPY_THRESHOLD = 5

    private val _entropyEvents = MutableSharedFlow<ContextEntropyEvent>(extraBufferCapacity = 1)
    val entropyEvents: SharedFlow<ContextEntropyEvent> = _entropyEvents

    sealed interface ContextEntropyEvent {
        object TriggerContextFlush : ContextEntropyEvent
    }

    /**
     * Call this every time the user sends a message.
     * In a production environment, this would run a lightweight embedding
     * comparison to check semantic distance. Here we simulate it heuristically.
     */
    fun analyzeEntropy(prompt: String) {
        // Extremely simple heuristic for topic extraction: grab the first word
        val topicKeyword = prompt.split(" ").firstOrNull()?.lowercase() ?: return
        
        topicWindow.add(topicKeyword)
        if (topicWindow.size > 10) {
            topicWindow.removeAt(0)
        }

        // Calculate unique topics in the window
        val uniqueTopics = topicWindow.distinct().size
        
        // If more than half the recent window consists of completely distinct topic shifts
        if (uniqueTopics >= ENTROPY_THRESHOLD && topicWindow.size >= 8) {
            triggerFlush()
        }
    }

    private fun triggerFlush() {
        topicWindow.clear()
        _entropyEvents.tryEmit(ContextEntropyEvent.TriggerContextFlush)
    }
}
