package com.example.llmapp.core.voice.pipeline

/**
 * Represents every possible state of the live audio conversation pipeline.
 * This is the single source of truth for UI rendering and pipeline logic.
 */
sealed class AudioPipelineState {
    /** Pipeline is idle. No audio capture or playback. */
    object Idle : AudioPipelineState()

    /** Microphone is active. Waiting for user to speak. */
    object Listening : AudioPipelineState()

    /** Speech has been detected. VAD has triggered. Capturing audio. */
    data class Capturing(val durationMs: Long = 0) : AudioPipelineState()

    /** Audio captured. Running STT inference. */
    data class Transcribing(val previewText: String = "") : AudioPipelineState()

    /** Transcript sent to LLM. Waiting for first token. */
    object Thinking : AudioPipelineState()

    /** Speculative cognition: preparing response before user finishes speaking. */
    object Speculating : AudioPipelineState()

    /** LLM is streaming. TTS is speaking the response. */
    data class Speaking(val currentSentence: String = "") : AudioPipelineState()

    /** User interrupted the AI. */
    object BargeIn : AudioPipelineState()

    /** An unrecoverable error occurred. */
    data class Error(val message: String) : AudioPipelineState()
}
