package com.example.llmapp.core.voice.pipeline

import com.example.llmapp.core.voice.tts.TtsEngine

/**
 * Handles the logic for when the user interrupts the AI (Barge-in).
 */
class BargeInManager(private val ttsEngine: TtsEngine) {
    fun handleBargeIn() {
        // Stop TTS and clear buffers
        ttsEngine.stop()
        // TODO: Notify cognitive layer to truncate AI memory turn
    }
}
