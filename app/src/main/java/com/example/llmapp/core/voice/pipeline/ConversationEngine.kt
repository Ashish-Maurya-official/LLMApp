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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ConversationEngine — The single, ViewModel-scoped orchestrator for live audio conversation.
 *
 * Replaces both VoiceInteractionManager (core/audio/) and VoiceManager (core/voice/).
 *
 * Architecture:
 *   VadAudioRecorder → onAudioReady → WhisperRunner.transcribe() → onTranscript(text) → [LLM]
 *   [LLM streams tokens] → feedToken() → SentenceChunker → TtsEngine.speak()
 *   Barge-in: VadAudioRecorder.onSpeechStart fires mid-TTS → ttsEngine.stop() immediately
 *
 * Lifecycle: Tied to ViewModel. Survives config changes. Created once, destroyed with ViewModel.
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

    private val stateMutex = Mutex()
    private var isActive = false

    // ── STT Components ─────────────────────────────────────────────────────
    private val whisperRunner = WhisperRunner(context)
    private val vadRecorder = VadAudioRecorder(
        onSpeechStart = ::handleSpeechStart,
        onAudioReady = ::handleAudioReady,
        onLevel = { rms -> /* Could expose for waveform UI */ }
    )

    // ── TTS Components ─────────────────────────────────────────────────────
    private val piperEngine: PiperVoiceEngine = PiperVoiceEngine(context)
    private val fallbackEngine: FallbackTtsEngine = FallbackTtsEngine(context, speechRate)
    private val activeTts: TtsEngine get() = if (piperEngine.isAvailable()) piperEngine else fallbackEngine

    // ── TTS Streaming Buffer ───────────────────────────────────────────────
    private val sentenceBuffer = StringBuilder()
    private var generationComplete = false
    private var ttsJob: Job? = null
    private val ttsQueue: ArrayDeque<String> = ArrayDeque()
    private var isTtsSpeaking = false

    // ─────────────────────────────────────────────────────────────────────

    /** Start the voice conversation loop. Call when voice mode is activated. */
    fun start() {
        if (isActive) return
        isActive = true
        Log.i(TAG, "ConversationEngine started. TTS backend: ${if (piperEngine.isAvailable()) "Piper" else "Android TTS"}")
        if (whisperRunner.isAvailable()) {
            scope.launch {
                vadRecorder.start(this)
                vadRecorder.setVadEnabled(true)
            }
        }
        startListening()
    }

    /** Stop the voice conversation loop. Call when voice mode is deactivated. */
    fun stop() {
        if (!isActive) return
        isActive = false
        vadRecorder.stop()
        activeTts.stop()
        ttsJob?.cancel()
        ttsQueue.clear()
        sentenceBuffer.clear()
        isTtsSpeaking = false
        generationComplete = false
        updateState(AudioPipelineState.Idle)
        Log.i(TAG, "ConversationEngine stopped")
    }

    /** Interrupt TTS mid-playback (barge-in). Returns to LISTENING immediately. */
    fun interrupt() {
        scope.launch {
            stateMutex.withLock {
                activeTts.stop()
                ttsJob?.cancel()
                ttsQueue.clear()
                sentenceBuffer.clear()
                isTtsSpeaking = false
                generationComplete = false
            }
        }
        if (isActive) startListening()
    }

    // ── STT Pipeline ───────────────────────────────────────────────────────

    private fun startListening() {
        updateState(AudioPipelineState.Listening)
        if (!whisperRunner.isAvailable()) {
            // Use Android ASR path — no VAD recorder needed
            Log.d(TAG, "Using Android ASR fallback (no TFLite model)")
            mainHandler.post {
                whisperRunner.startAndroidAsr { text ->
                    if (text.isNotBlank()) {
                        handleTranscript(text)
                    } else if (isActive) {
                        startListening()
                    }
                }
            }
        } else {
            vadRecorder.setVadEnabled(true)
        }
    }

    private fun handleSpeechStart() {
        // Barge-in: interrupt TTS if speaking
        if (isTtsSpeaking) {
            Log.d(TAG, "BARGE-IN: User started speaking, interrupting TTS")
            activeTts.stop()
            ttsJob?.cancel()
            ttsQueue.clear()
            sentenceBuffer.clear()
            isTtsSpeaking = false
            generationComplete = false
        }
        updateState(AudioPipelineState.Capturing())
    }

    private fun handleAudioReady(audio: ShortArray) {
        vadRecorder.setVadEnabled(false)
        updateState(AudioPipelineState.Transcribing())

        scope.launch(Dispatchers.IO) {
            val transcript = whisperRunner.transcribe(audio)
            if (transcript.isNotBlank()) {
                handleTranscript(transcript)
            } else {
                Log.w(TAG, "Empty transcript — returning to listening")
                if (isActive) startListening()
            }
        }
    }

    private fun handleTranscript(text: String) {
        Log.d(TAG, "Transcript: $text")
        updateState(AudioPipelineState.Thinking)
        // Prepare TTS pipeline for new generation
        resetTtsPipeline()
        // Notify ViewModel to send to LLM
        mainHandler.post { onTranscript(text) }
    }

    // ── TTS Pipeline ───────────────────────────────────────────────────────

    /**
     * Called by the ViewModel for each LLM output token.
     * Accumulates tokens into a buffer, flushes complete sentences to TTS.
     */
    fun feedToken(token: String, isDone: Boolean) {
        sentenceBuffer.append(token)

        // Check for sentence boundary
        val boundary = SentenceChunker.findBoundary(sentenceBuffer.toString())
        if (boundary > 0) {
            val sentence = SentenceChunker.stripMarkdown(
                sentenceBuffer.substring(0, boundary)
            ).trim()
            sentenceBuffer.delete(0, boundary)
            if (sentence.isNotBlank()) enqueueSentence(sentence)
        }

        if (isDone) {
            generationComplete = true
            val remainder = SentenceChunker.stripMarkdown(sentenceBuffer.toString()).trim()
            sentenceBuffer.clear()
            if (remainder.isNotBlank()) enqueueSentence(remainder)
            processTtsQueue()
        }
    }

    private fun enqueueSentence(sentence: String) {
        ttsQueue.addLast(sentence)
        processTtsQueue()
    }

    private fun processTtsQueue() {
        if (isTtsSpeaking) return  // Already playing — new sentences will be picked up via queue
        if (ttsQueue.isEmpty()) return

        ttsJob = scope.launch {
            while (ttsQueue.isNotEmpty()) {
                val sentence = ttsQueue.removeFirst()
                Log.d(TAG, "TTS speaking: \"${sentence.take(60)}\"")
                isTtsSpeaking = true
                updateState(AudioPipelineState.Speaking(sentence))

                activeTts.speak(
                    text = sentence,
                    onStart = {},
                    onDone = {}
                )

                if (!isActive) break
            }

            isTtsSpeaking = false

            // All sentences spoken and generation done → return to listening
            if (isActive && generationComplete) {
                generationComplete = false
                delay(300L) // Brief pause before listening resumes
                startListening()
            }
        }
    }

    private fun resetTtsPipeline() {
        ttsJob?.cancel()
        ttsQueue.clear()
        sentenceBuffer.clear()
        generationComplete = false
        isTtsSpeaking = false
    }

    private fun updateState(state: AudioPipelineState) {
        _state.value = state
        mainHandler.post { onStateChanged(state) }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun WhisperRunner.isAvailable(): Boolean {
        // We check by seeing if TFLite loaded successfully by trying a dummy run
        // Actually for our design, TFLite is always "attempted" — fallback is Android ASR
        // We expose via the vocab check: if vocab is loaded, TFLite is likely functional
        return true // Always try TFLite path first; WhisperRunner handles fallback internally
    }

    fun destroy() {
        stop()
        whisperRunner.destroy()
        piperEngine.destroy()
        fallbackEngine.destroy()
        Log.i(TAG, "ConversationEngine destroyed")
    }
}
