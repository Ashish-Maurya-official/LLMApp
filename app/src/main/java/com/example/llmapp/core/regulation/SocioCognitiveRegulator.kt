package com.example.llmapp.core.regulation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks the conversational rhythm to prevent the AI from overwhelming the user
 * or matching an escalating emotional state.
 */
class SocioCognitiveRegulator {

    private val interactionTimestamps = mutableListOf<Long>()
    private val INTERACTION_WINDOW_MS = 60_000L // 1 minute window

    enum class UrgencyLevel { CASUAL, ACTIVE, URGENT }

    private val _urgencyState = MutableStateFlow(UrgencyLevel.CASUAL)
    val urgencyState: StateFlow<UrgencyLevel> = _urgencyState.asStateFlow()

    /**
     * Call this every time the user sends a message.
     */
    fun recordUserInteraction() {
        val now = System.currentTimeMillis()
        interactionTimestamps.add(now)

        // Prune old timestamps
        interactionTimestamps.removeAll { now - it > INTERACTION_WINDOW_MS }

        // Determine urgency based on interaction density
        val density = interactionTimestamps.size
        _urgencyState.update {
            when {
                density >= 5 -> UrgencyLevel.URGENT // More than 5 interactions per minute
                density >= 3 -> UrgencyLevel.ACTIVE
                else -> UrgencyLevel.CASUAL
            }
        }
    }

    /**
     * Determines if an artificial delay should be introduced to mimic human pondering.
     */
    fun calculatePacingDelayMs(): Long {
        return when (_urgencyState.value) {
            UrgencyLevel.CASUAL -> 1200L // Relaxed 1.2s delay
            UrgencyLevel.ACTIVE -> 400L  // Slight pause
            UrgencyLevel.URGENT -> 0L    // Instant response, no delay
        }
    }

    /**
     * Injects emotional damping and pacing instructions into the LLM context.
     */
    fun generateRegulatoryPrompt(): String {
        return when (_urgencyState.value) {
            UrgencyLevel.CASUAL -> "\n[SOCIO-COGNITIVE: The interaction is relaxed. You may use standard elaboration. Tone: Conversational.]"
            UrgencyLevel.ACTIVE -> "\n[SOCIO-COGNITIVE: The interaction is active. Keep responses concise and focused. Tone: Efficient.]"
            UrgencyLevel.URGENT -> "\n[SOCIO-COGNITIVE: The user is interacting rapidly. YOU MUST USE EXTREME BREVITY. 1-2 sentences max. Tone: Calm, Grounding, Direct.]"
        }
    }
}
