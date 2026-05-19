package com.example.llmapp.core.voice.tts

/**
 * Common interface for all TTS engines.
 * Both PiperVoiceEngine and FallbackTtsEngine implement this.
 */
interface TtsEngine {
    /** Returns true if this engine is loaded and ready to synthesize. */
    fun isAvailable(): Boolean

    /**
     * Synthesizes text and plays it via AudioTrack.
     * Returns when audio playback is complete.
     * Should be called from a coroutine on Dispatchers.Default or IO.
     * The [onStart] callback is invoked on the caller's thread when audio begins.
     * The [onDone] callback is invoked when audio finishes (or is cancelled).
     */
    suspend fun speak(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {})

    /** Immediately stops any ongoing audio playback. */
    fun stop()

    /** Releases all resources. */
    fun destroy()
}
