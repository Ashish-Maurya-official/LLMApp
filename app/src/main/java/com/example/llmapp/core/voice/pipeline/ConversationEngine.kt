package com.example.llmapp.core.voice.pipeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.llmapp.core.voice.stt.WhisperRunner
import com.example.llmapp.core.voice.tts.FallbackTtsEngine
import com.example.llmapp.core.voice.tts.PiperVoiceEngine
import com.example.llmapp.core.voice.tts.SentenceChunker
import com.example.llmapp.core.voice.tts.TtsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.llmapp.core.cognition.CognitiveRouter

/**
 * ConversationEngine — ViewModel-scoped orchestrator for live audio conversation.
 *
 * ## Architecture (v3 — Android ASR)
 *
 * Uses Android's native `SpeechRecognizer` for microphone capture, VAD, and
 * partial results. This delegates hardware DSP (AEC, noise suppression, AGC)
 * to the OS, eliminating custom AudioRecord buffer management.
 *
 * ### Partial Transcripts
 *   `onPartialResults` from the OS is forwarded via `onPartialTranscript` so
 *   the UI can display streaming words in real-time as the user speaks.
 *
 * ### Barge-in
 *   `onBeginningOfSpeech` from the OS triggers `handleSpeechStart()`, which
 *   cancels any active TTS playback immediately.
 *
 * ### TTS Queue
 *   LLM tokens are sentence-chunked and enqueued. A single coroutine Job
 *   drains the queue, preventing concurrent `speak()` calls.
 */
class ConversationEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPartialTranscript: (String) -> Unit = {},
    private val onTranscript: (String) -> Unit,
    private val onStateChanged: (AudioPipelineState) -> Unit,
    private val speechRate: Float = 0.92f
) {
    private val TAG = "ConversationEngine"
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── State ──────────────────────────────────────────────────────────────
    private val _state = MutableStateFlow<AudioPipelineState>(AudioPipelineState.Idle)
    val state: StateFlow<AudioPipelineState> = _state.asStateFlow()

    @Volatile private var isActive = false

    private val whisperRunner = WhisperRunner(context)
    
    // ── TTS ────────────────────────────────────────────────────────────────
    private val piperEngine: PiperVoiceEngine = PiperVoiceEngine(context)
    private val fallbackEngine: FallbackTtsEngine = FallbackTtsEngine(context, speechRate)
    private val activeTts: TtsEngine get() = if (piperEngine.isAvailable()) piperEngine else fallbackEngine

    // ── Pipeline Architecture Scaffolds ──────────────────────────────────────
    private val cognitiveRouter = CognitiveRouter()
    private val turnManager = TurnManager()
    private val bargeInManager = BargeInManager(activeTts) // Will use activeTts dynamically inside
    private val latencyTracker = LatencyTracker()

    // ── TTS streaming state ─────────────────────────────────────────────────
    private val sentenceBuffer = StringBuilder()
    @Volatile private var generationComplete = false
    @Volatile private var isTtsSpeaking = false
    private val ttsQueue = ArrayDeque<String>()
    private var ttsJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────

    /** Start the voice conversation loop. Called once when voice mode activates. */
    fun start() {
        if (isActive) return
        isActive = true
        Log.i(TAG, "ConversationEngine started. TTS: ${if (piperEngine.isAvailable()) "Piper" else "Android TTS"}")
        beginListening()
    }

    /** Stop the voice conversation loop. Called when voice mode deactivates. */
    fun stop() {
        if (!isActive) return
        isActive = false

        // Stop TTS immediately
        activeTts.stop()
        ttsJob?.cancel()
        ttsJob = null

        // Stop microphone hardware — full release since session is over
        whisperRunner.stopAndroidAsr()

        // Reset all pipeline buffers
        synchronized(ttsQueue) { ttsQueue.clear() }
        sentenceBuffer.clear()
        isTtsSpeaking = false
        generationComplete = false

        updateState(AudioPipelineState.Idle)
        Log.i(TAG, "ConversationEngine stopped")
    }

    /**
     * Barge-in: called from UI button to interrupt TTS mid-playback.
     * The mic is already on (continuous recording), so we just stop TTS and
     * the VAD will pick up the new speech immediately.
     */
    fun interrupt() {
        Log.d(TAG, "interrupt() called")
        activeTts.stop()
        ttsJob?.cancel()
        ttsJob = null
        synchronized(ttsQueue) { ttsQueue.clear() }
        sentenceBuffer.clear()
        isTtsSpeaking = false
        generationComplete = false

        // Android ASR mode: start a new ASR session
        if (isActive) {
            beginListeningAndroidAsr()
        }
    }

    // ── STT Pipeline ───────────────────────────────────────────────────────

    /**
     * Entry point for starting/resuming the listening loop.
     */
    private fun beginListening() {
        updateState(AudioPipelineState.Listening)
        beginListeningAndroidAsr()
    }

    private fun beginListeningAndroidAsr() {
        Log.d(TAG, "Android ASR listening mode")
        mainHandler.post {
            whisperRunner.startAndroidAsr(
                onFinal = { text ->
                    if (!isActive) return@startAndroidAsr
                    if (text.isNotBlank()) {
                        handleTranscript(text)
                    } else {
                        // Restart ASR on empty result (user may not have spoken)
                        if (isActive) beginListeningAndroidAsr()
                    }
                },
                onPartial = { partialText ->
                    if (!isActive) return@startAndroidAsr
                    updateState(AudioPipelineState.Transcribing())
                    onPartialTranscript(partialText)
                },
                onSpeechStart = {
                    if (!isActive) return@startAndroidAsr
                    handleSpeechStart()
                }
            )
        }
    }

    /**
     * Called when VAD detects speech onset.
     * Key use: barge-in detection — if TTS is playing when user starts speaking,
     * we immediately stop it. This works because the mic is ALWAYS on.
     */
    private fun handleSpeechStart() {
        turnManager.onUserStartSpeaking()
        latencyTracker.onVadStart()
        if (isTtsSpeaking) {
            Log.d(TAG, "BARGE-IN: speech detected mid-TTS — stopping playback")
            updateState(AudioPipelineState.BargeIn)
            bargeInManager.handleBargeIn()
            
            ttsJob?.cancel()
            ttsJob = null
            synchronized(ttsQueue) { ttsQueue.clear() }
            sentenceBuffer.clear()
            isTtsSpeaking = false
            generationComplete = false
        }
        updateState(AudioPipelineState.Capturing())
    }



    private fun handleTranscript(text: String) {
        Log.i(TAG, "Transcript: \"$text\"")
        latencyTracker.onRequestSent()
        
        // Pass to cognitive layer (scaffold)
        cognitiveRouter.route(text)
        
        updateState(AudioPipelineState.Thinking)
        resetTtsPipeline()
        mainHandler.post { onTranscript(text) }
    }

    // ── TTS Pipeline ───────────────────────────────────────────────────────

    /**
     * Called by the ViewModel for each LLM output token.
     * Accumulates tokens, detects sentence boundaries, and streams to TTS.
     * Thread-safe: may be called from any thread.
     */
    fun feedToken(token: String, isDone: Boolean) {
        sentenceBuffer.append(token)

        while (true) {
            val text = sentenceBuffer.toString()
            val boundary = SentenceChunker.findBoundary(text)
            if (boundary > 0) {
                val sentence = SentenceChunker.stripMarkdown(text.substring(0, boundary)).trim()
                sentenceBuffer.delete(0, boundary)
                if (sentence.isNotBlank()) enqueue(sentence)
            } else {
                break
            }
        }

        if (isDone) {
            generationComplete = true
            val remainder = SentenceChunker.stripMarkdown(sentenceBuffer.toString()).trim()
            sentenceBuffer.clear()
            if (remainder.isNotBlank()) enqueue(remainder)
            drainTtsQueue()
        }
    }

    private fun enqueue(sentence: String) {
        synchronized(ttsQueue) { ttsQueue.addLast(sentence) }
        drainTtsQueue()
    }

    /**
     * Drains the TTS queue. Ensures only ONE coroutine is playing at a time.
     * If a TTS job is already running, it will pick up new sentences naturally
     * from the queue when the current sentence finishes.
     */
    private fun drainTtsQueue() {
        // Guard: only start a new job if one isn't already active
        if (isTtsSpeaking) return
        if (synchronized(ttsQueue) { ttsQueue.isEmpty() }) return

        isTtsSpeaking = true
        ttsJob = scope.launch {
            try {
                while (true) {
                    val sentence = synchronized(ttsQueue) {
                        if (ttsQueue.isNotEmpty()) ttsQueue.removeFirst() else null
                    } ?: break

                    if (!isActive) break

                    Log.d(TAG, "TTS → \"${sentence.take(80)}\"")
                    
                    if (sentenceBuffer.isEmpty() && sentence == synchronized(ttsQueue) { ttsQueue.firstOrNull() }) {
                        latencyTracker.onFirstTokenReceived()
                    }
                    
                    turnManager.onAiStartSpeaking()
                    updateState(AudioPipelineState.Speaking(sentence))

                    activeTts.speak(text = sentence)

                    // Check for coroutine cancellation after each sentence
                    if (!isActive || !isCoroutineActive()) break
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "TTS job cancelled (barge-in or stop)")
                throw e
            } finally {
                isTtsSpeaking = false
            }

            // All sentences done — return to listening if generation is complete
            if (isActive && generationComplete) {
                generationComplete = false
                delay(250L)  // brief natural pause before mic reopens

                beginListeningAndroidAsr()
                updateState(AudioPipelineState.Listening)
            }
        }
    }

    private fun isCoroutineActive(): Boolean = scope.isActive

    private fun resetTtsPipeline() {
        activeTts.stop()
        ttsJob?.cancel()
        ttsJob = null
        synchronized(ttsQueue) { ttsQueue.clear() }
        sentenceBuffer.clear()
        generationComplete = false
        isTtsSpeaking = false
    }

    private fun updateState(newState: AudioPipelineState) {
        _state.value = newState
        mainHandler.post { onStateChanged(newState) }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun destroy() {
        stop()
        whisperRunner.destroy()
        piperEngine.destroy()
        fallbackEngine.destroy()
        Log.i(TAG, "ConversationEngine destroyed")
    }
}
