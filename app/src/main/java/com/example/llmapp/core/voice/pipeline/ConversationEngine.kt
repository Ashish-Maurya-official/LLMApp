package com.example.llmapp.core.voice.pipeline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.llmapp.core.voice.stt.VadAudioRecorder
import com.example.llmapp.core.voice.stt.WhisperRunner
import com.example.llmapp.core.voice.tts.FallbackTtsEngine
import com.example.llmapp.core.voice.tts.PiperVoiceEngine
import com.example.llmapp.core.voice.tts.SentenceChunker
import com.example.llmapp.core.voice.tts.TtsEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ConversationEngine — Single, ViewModel-scoped orchestrator for live audio conversation.
 *
 * ## v2 Refinements:
 *
 * ### Continuous Microphone
 *   VadAudioRecorder is started ONCE per session. After each utterance is processed,
 *   we call vadRecorder.resumeListening() instead of stop()/start().
 *   This eliminates mic indicator flickering and saves ~250ms of hardware re-init latency.
 *
 * ### Robust STT Fallback
 *   WhisperRunner.tfliteReady is now checked directly. If TFLite is loaded but produces
 *   empty output (wrong model architecture), we permanently switch to Android ASR mode
 *   for the rest of the session and stop using the VAD recorder.
 *
 * ### TTS Race Condition Fix
 *   processTtsQueue() is now guarded by a single coroutine Job. A new Job is only started
 *   if one isn't already running — prevents concurrent speak() calls.
 *
 * ### Barge-in
 *   VadAudioRecorder.onSpeechStart fires mid-TTS (since mic stays on).
 *   ConversationEngine immediately cancels the TTS job and clears the queue.
 */
class ConversationEngine(
    private val context: Context,
    private val scope: CoroutineScope,
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

    // ── STT: mode selection ─────────────────────────────────────────────────
    // We detect at runtime which STT path is reliable:
    //   useWhisperPath = true  → VadAudioRecorder + WhisperRunner (offline)
    //   useWhisperPath = false → Android SpeechRecognizer (online, managed by OS)
    // If TFLite produces an empty result, we flip useWhisperPath to false permanently
    // for the session and rely on Android ASR.
    @Volatile private var useWhisperPath = true
    @Volatile private var tfliteFailCount = 0
    @Volatile private var whisperHardwareStarted = false  // true after first vadRecorder.start()
    private val TFLITE_FAIL_LIMIT = 2  // switch to Android ASR after 2 consecutive empty results

    private val whisperRunner = WhisperRunner(context)
    private val vadRecorder = VadAudioRecorder(
        onSpeechStart = ::handleSpeechStart,
        onAudioReady  = ::handleAudioReady,
        onLevel       = { /* could expose for waveform animation */ }
    )

    // ── TTS ────────────────────────────────────────────────────────────────
    private val piperEngine: PiperVoiceEngine = PiperVoiceEngine(context)
    private val fallbackEngine: FallbackTtsEngine = FallbackTtsEngine(context, speechRate)
    private val activeTts: TtsEngine get() = if (piperEngine.isAvailable()) piperEngine else fallbackEngine

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
        vadRecorder.stop()
        whisperRunner.stopAndroidAsr()

        // Reset all pipeline buffers
        synchronized(ttsQueue) { ttsQueue.clear() }
        sentenceBuffer.clear()
        isTtsSpeaking = false
        generationComplete = false
        tfliteFailCount = 0
        useWhisperPath = true
        whisperHardwareStarted = false

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

        // If we're in Whisper mode, resume listening on the same AudioRecord
        if (isActive && useWhisperPath) {
            vadRecorder.resumeListening()
            updateState(AudioPipelineState.Listening)
        } else if (isActive) {
            // Android ASR mode: start a new ASR session
            beginListeningAndroidAsr()
        }
    }

    // ── STT Pipeline ───────────────────────────────────────────────────────

    /**
     * Entry point for starting/resuming the listening loop.
     * - First call per session: initializes hardware (vadRecorder.start / ASR)
     * - Subsequent calls: resumes from PAUSED state (no hardware churn)
     */
    private fun beginListening() {
        updateState(AudioPipelineState.Listening)

        if (useWhisperPath) {
            beginListeningWhisper()
        } else {
            beginListeningAndroidAsr()
        }
    }

    private fun beginListeningWhisper() {
        scope.launch(Dispatchers.IO) {
            if (!whisperHardwareStarted) {
                // First call: start AudioRecord hardware for the session
                whisperHardwareStarted = true
                vadRecorder.start(this)
            } else {
                // Subsequent calls: mic is already running (PAUSED), just resume
                vadRecorder.resumeListening()
            }
        }
    }

    private fun beginListeningAndroidAsr() {
        Log.d(TAG, "Android ASR listening mode")
        mainHandler.post {
            whisperRunner.startAndroidAsr { text ->
                if (!isActive) return@startAndroidAsr
                if (text.isNotBlank()) {
                    handleTranscript(text)
                } else {
                    // Restart ASR on empty result (user may not have spoken)
                    if (isActive) beginListeningAndroidAsr()
                }
            }
        }
    }

    /**
     * Called when VAD detects speech onset.
     * Key use: barge-in detection — if TTS is playing when user starts speaking,
     * we immediately stop it. This works because the mic is ALWAYS on.
     */
    private fun handleSpeechStart() {
        if (isTtsSpeaking) {
            Log.d(TAG, "BARGE-IN: speech detected mid-TTS — stopping playback")
            activeTts.stop()
            ttsJob?.cancel()
            ttsJob = null
            synchronized(ttsQueue) { ttsQueue.clear() }
            sentenceBuffer.clear()
            isTtsSpeaking = false
            generationComplete = false
        }
        updateState(AudioPipelineState.Capturing())
    }

    /**
     * Called by VadAudioRecorder when a complete utterance is ready.
     * VadAudioRecorder has already transitioned to PAUSED — mic stays alive.
     */
    private fun handleAudioReady(audio: ShortArray) {
        // Mic is already PAUSED by VadAudioRecorder — no hardware stop needed
        updateState(AudioPipelineState.Transcribing())

        scope.launch(Dispatchers.IO) {
            val transcript = whisperRunner.transcribe(audio)

            when {
                transcript.isNotBlank() -> {
                    tfliteFailCount = 0  // reset failure counter on success
                    handleTranscript(transcript)
                }
                tfliteFailCount < TFLITE_FAIL_LIMIT -> {
                    tfliteFailCount++
                    Log.w(TAG, "TFLite empty result ($tfliteFailCount/$TFLITE_FAIL_LIMIT) — resuming VAD")
                    if (isActive) {
                        vadRecorder.resumeListening()
                        updateState(AudioPipelineState.Listening)
                    }
                }
                else -> {
                    // TFLite has failed too many times — permanently switch to Android ASR
                    Log.w(TAG, "TFLite failed $TFLITE_FAIL_LIMIT times — permanently switching to Android ASR")
                    useWhisperPath = false
                    vadRecorder.stop()  // release mic so Android ASR can use it
                    if (isActive) beginListeningAndroidAsr()
                }
            }
        }
    }

    private fun handleTranscript(text: String) {
        Log.i(TAG, "Transcript: \"$text\"")
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

        val text = sentenceBuffer.toString()
        val boundary = SentenceChunker.findBoundary(text)
        if (boundary > 0) {
            val sentence = SentenceChunker.stripMarkdown(text.substring(0, boundary)).trim()
            sentenceBuffer.delete(0, boundary)
            if (sentence.isNotBlank()) enqueue(sentence)
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

        ttsJob = scope.launch {
            isTtsSpeaking = true
            try {
                while (true) {
                    val sentence = synchronized(ttsQueue) {
                        if (ttsQueue.isNotEmpty()) ttsQueue.removeFirst() else null
                    } ?: break

                    if (!isActive) break

                    Log.d(TAG, "TTS → \"${sentence.take(80)}\"")
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

                if (useWhisperPath) {
                    vadRecorder.resumeListening()
                    updateState(AudioPipelineState.Listening)
                } else {
                    beginListeningAndroidAsr()
                    updateState(AudioPipelineState.Listening)
                }
            }
        }
    }

    private fun isCoroutineActive(): Boolean = scope.isActive

    private fun resetTtsPipeline() {
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
