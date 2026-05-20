package com.example.llmapp.core.voice.tts

/**
 * Defines the prosodic parameters for a specific emotional state.
 */
data class EmotionProsody(
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f,
    val energy: Float = 1.0f
)
