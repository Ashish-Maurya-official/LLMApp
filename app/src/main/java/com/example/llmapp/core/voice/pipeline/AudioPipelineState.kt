package com.example.llmapp.core.voice.pipeline

/**
 * States of the live audio conversation pipeline.
 */
sealed class AudioPipelineState {
    object Idle : AudioPipelineState()

    object Listening : AudioPipelineState()

    data class Capturing(val durationMs: Long = 0) : AudioPipelineState()

    data class Transcribing(val previewText: String = "") : AudioPipelineState()

    object Thinking : AudioPipelineState()

    object Speculating : AudioPipelineState()

    data class Speaking(val currentSentence: String = "") : AudioPipelineState()

    object BargeIn : AudioPipelineState()

    data class Error(val message: String) : AudioPipelineState()
}
