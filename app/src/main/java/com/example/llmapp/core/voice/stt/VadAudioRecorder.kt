package com.example.llmapp.core.voice.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * Production-grade VAD audio recorder — Refined v2.
 *
 * ## Key improvements over v1:
 *
 * ### 1. Continuous Microphone (No Flickering)
 *   AudioRecord is created ONCE per session and kept alive the entire time.
 *   Instead of stop()/start() per utterance, we use an internal state machine:
 *     CALIBRATING → LISTENING → CAPTURING → PAUSED → LISTENING → ...
 *   The mic indicator stays solid (no flickering) and latency drops by ~250ms.
 *
 * ### 2. Dynamic Noise Floor (EMA Auto-Calibration)
 *   Uses an Exponential Moving Average to track ambient noise level in real-time.
 *   - Initial 1.5s calibration period measures baseline noise.
 *   - During LISTENING, the EMA updates only on quiet frames (speech doesn't
 *     corrupt the noise estimate).
 *   - VAD threshold = max(minThreshold, noiseFloor × SNR_MULTIPLIER)
 *   This handles quiet rooms, noisy offices, and AC/fan noise automatically.
 *
 * ### 3. CPU Safety (No Busy Loops)
 *   AudioRecord.read() can return error codes (negative values):
 *     ERROR (-1), ERROR_INVALID_OPERATION (-3), ERROR_BAD_VALUE (-2), ERROR_DEAD_OBJECT (-6)
 *   These are now explicitly detected and break the recording loop with a diagnostic log,
 *   instead of spinning at 100% CPU on `continue`.
 */
class VadAudioRecorder(
    private val sampleRate: Int = 16000,
    private val speechHoldMs: Long = 700L,      // Hold speech open N ms after last active frame
    private val preRollMs: Int = 300,            // Pre-roll ring buffer (captures utterance onset)
    private val calibrationMs: Int = 1500,       // Initial noise floor calibration period
    private val onSpeechStart: () -> Unit = {},
    private val onAudioReady: (ShortArray) -> Unit,
    private val onLevel: (Float) -> Unit = {}
) {

    // ── Internal State Machine ─────────────────────────────────────────────
    private enum class RecordingState {
        IDLE,          // AudioRecord not created yet
        CALIBRATING,   // Measuring ambient noise floor (first calibrationMs)
        LISTENING,     // Waiting for speech energy above threshold
        CAPTURING,     // Actively capturing a speech utterance
        PAUSED         // Mic alive, data discarded while STT/LLM is processing
    }

    private val TAG = "VadAudioRecorder"

    // ── Dynamic Noise Floor (EMA) ──────────────────────────────────────────
    // Threshold = max(MIN_THRESHOLD, noiseFloorEma × SNR_MULTIPLIER)
    // EMA tracks ambient noise, updating only on quiet frames.
    private var noiseFloorEma = 120f      // Initial conservative estimate
    private val emaAlpha = 0.005f         // Very slow EMA — stable noise floor
    private val SNR_MULTIPLIER = 3.0f     // 3× noise floor = speech onset
    private val MIN_THRESHOLD = 80f       // Floor: never trigger below 80 RMS
    private val MAX_THRESHOLD = 1500f     // Ceiling: never require above 1500 RMS

    // ── Calibration ────────────────────────────────────────────────────────
    private val CHUNK_SAMPLES = sampleRate * 50 / 1000  // 50ms chunks = 800 samples @ 16kHz
    private val calibrationChunks = (calibrationMs.toFloat() / 50f).toInt()
    private var calibrationCount = 0
    private var calibrationAccum = 0.0

    // ── Buffers ────────────────────────────────────────────────────────────
    private val preRollSamples = sampleRate * preRollMs / 1000
    private val preRollBuffer = ArrayDeque<Short>(preRollSamples + CHUNK_SAMPLES)
    private val speechBuffer = ArrayList<Short>(sampleRate * 10) // pre-alloc 10s

    // ── AudioRecord ────────────────────────────────────────────────────────
    private val hwBufferSize: Int by lazy {
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // Validate: getMinBufferSize can return ERROR or ERROR_BAD_VALUE
        if (min <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize() returned error: $min")
            4096
        } else {
            min * 4  // 4× to avoid underruns
        }
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    @Volatile private var state = RecordingState.IDLE
    @Volatile private var lastSoundTimeMs = 0L

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Create the AudioRecord hardware resource and begin the calibration+listening loop.
     * Call ONCE per voice session. Keep the recorder alive throughout.
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (state != RecordingState.IDLE) {
            Log.w(TAG, "start() called while state=$state — ignored")
            return
        }

        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min == AudioRecord.ERROR || min == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Cannot create AudioRecord — getMinBufferSize failed: $min")
            return
        }

        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            hwBufferSize
        )

        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${ar.state})")
            ar.release()
            return
        }

        // Reset tracking state
        noiseFloorEma = 120f
        calibrationCount = 0
        calibrationAccum = 0.0
        preRollBuffer.clear()
        speechBuffer.clear()
        lastSoundTimeMs = 0L
        state = RecordingState.CALIBRATING

        audioRecord = ar
        ar.startRecording()
        Log.i(TAG, "AudioRecord started — calibrating noise floor for ${calibrationMs}ms…")

        recordingJob = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(CHUNK_SAMPLES)
            while (isActive && state != RecordingState.IDLE) {
                val read = ar.read(chunk, 0, chunk.size)

                // ── CPU Safety: hard-break on any error code ───────────────
                when {
                    read == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord.ERROR_INVALID_OPERATION — breaking loop")
                        break
                    }
                    read == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord.ERROR_BAD_VALUE — breaking loop")
                        break
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.e(TAG, "AudioRecord.ERROR_DEAD_OBJECT — mic lost, breaking loop")
                        break
                    }
                    read < 0 -> {
                        Log.e(TAG, "AudioRecord.read() unknown error $read — breaking loop")
                        break
                    }
                    read == 0 -> {
                        // Benign empty read — yield and retry (not an error)
                        yield()
                        continue
                    }
                }

                val samples = if (read == chunk.size) chunk else chunk.copyOfRange(0, read)
                val rms = computeRms(samples)
                onLevel(rms)

                // ── State machine dispatch ─────────────────────────────────
                when (state) {
                    RecordingState.CALIBRATING -> onCalibrate(rms, samples)
                    RecordingState.LISTENING   -> onListening(rms, samples)
                    RecordingState.CAPTURING   -> onCapturing(rms, samples)
                    RecordingState.PAUSED      -> { /* Mic alive; discard data */ }
                    RecordingState.IDLE        -> break
                }
            }

            Log.i(TAG, "Recording loop exited (state=$state)")
        }
    }

    /**
     * Resume listening AFTER STT/LLM has processed the previous utterance.
     * Mic stays alive — this is just a state transition, NOT a hardware restart.
     */
    fun resumeListening() {
        if (state == RecordingState.IDLE) {
            Log.w(TAG, "resumeListening() called while IDLE — ignored")
            return
        }
        preRollBuffer.clear()
        speechBuffer.clear()
        lastSoundTimeMs = 0L
        state = RecordingState.LISTENING
        Log.d(TAG, "Resumed listening (noiseFloor=%.1f → threshold=%.1f)"
            .format(noiseFloorEma, computeThreshold()))
    }

    /**
     * Pause listening while STT/LLM is running. Mic stays alive and warm,
     * but audio is discarded. Call this immediately after onAudioReady fires.
     */
    fun pauseListening() {
        if (state == RecordingState.CAPTURING || state == RecordingState.LISTENING) {
            state = RecordingState.PAUSED
            Log.d(TAG, "Mic paused (keeping hardware alive)")
        }
    }

    /**
     * Full hardware shutdown. Call ONLY when the voice session ends.
     * Releases the microphone and stops the OS indicator.
     */
    fun stop() {
        val prev = state
        state = RecordingState.IDLE
        recordingJob?.cancel()
        recordingJob = null
        try {
            val ar = audioRecord
            audioRecord = null
            ar?.stop()
            ar?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        preRollBuffer.clear()
        speechBuffer.clear()
        Log.i(TAG, "AudioRecord stopped and released (was $prev)")
    }

    /** Returns the current dynamically-computed VAD threshold. */
    fun currentThreshold(): Float = computeThreshold()

    // ── State Handlers ─────────────────────────────────────────────────────

    private fun onCalibrate(rms: Float, samples: ShortArray) {
        calibrationAccum += rms
        calibrationCount++

        // Fill pre-roll during calibration
        for (s in samples) {
            if (preRollBuffer.size >= preRollSamples) preRollBuffer.removeFirst()
            preRollBuffer.addLast(s)
        }

        if (calibrationCount >= calibrationChunks) {
            // Calibration complete — set initial noise floor from measured average
            val measured = (calibrationAccum / calibrationCount).toFloat()
            noiseFloorEma = measured.coerceIn(40f, 600f)
            state = RecordingState.LISTENING
            Log.i(TAG, "Calibration done — noise floor: %.1f  VAD threshold: %.1f"
                .format(noiseFloorEma, computeThreshold()))
        }
    }

    private fun onListening(rms: Float, samples: ShortArray) {
        // Update EMA only when quiet (speech would inflate the noise floor)
        if (rms < computeThreshold()) {
            noiseFloorEma = emaAlpha * rms + (1f - emaAlpha) * noiseFloorEma
        }

        if (rms > computeThreshold()) {
            // Speech onset detected
            lastSoundTimeMs = System.currentTimeMillis()
            state = RecordingState.CAPTURING

            // Drain pre-roll into speechBuffer to capture utterance onset
            speechBuffer.addAll(preRollBuffer)
            preRollBuffer.clear()
            speechBuffer.addAll(samples.toList())

            onSpeechStart()
            Log.d(TAG, "Speech onset — RMS=%.1f  threshold=%.1f  noiseFloor=%.1f"
                .format(rms, computeThreshold(), noiseFloorEma))
        } else {
            // Silent — maintain pre-roll ring buffer
            for (s in samples) {
                if (preRollBuffer.size >= preRollSamples) preRollBuffer.removeFirst()
                preRollBuffer.addLast(s)
            }
        }
    }

    private fun onCapturing(rms: Float, samples: ShortArray) {
        speechBuffer.addAll(samples.toList())

        if (rms > computeThreshold()) {
            lastSoundTimeMs = System.currentTimeMillis()
        }

        val silenceDuration = System.currentTimeMillis() - lastSoundTimeMs
        if (silenceDuration >= speechHoldMs) {
            // Silence held long enough — utterance is complete
            val audio = speechBuffer.toShortArray()
            speechBuffer.clear()
            preRollBuffer.clear()

            // Transition to PAUSED — keeps mic alive while STT runs
            state = RecordingState.PAUSED

            val durationMs = audio.size / (sampleRate / 1000)
            Log.d(TAG, "Utterance complete — ${durationMs}ms / ${audio.size} samples")

            // Fire callback on the IO coroutine's thread — caller dispatches as needed
            onAudioReady(audio)
        }
    }

    // ── DSP ────────────────────────────────────────────────────────────────

    private fun computeThreshold(): Float =
        (noiseFloorEma * SNR_MULTIPLIER).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    private fun computeRms(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in buffer) {
            val d = s.toDouble()
            sumSq += d * d
        }
        return sqrt(sumSq / buffer.size).toFloat()
    }
}
