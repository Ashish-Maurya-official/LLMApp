package com.example.llmapp.core.voice.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * Production-grade VAD audio recorder with:
 *  - Continuous mic recording (no tearing down AudioRecord on speech end)
 *  - VAD enable/disable toggle for state management
 *  - Self-calibrating noise floor tracker (EMA) for dynamic VAD thresholding
 *  - 300ms pre-roll ring buffer (never clips the beginning of speech)
 *  - RMS-based energy detection over 50ms windows
 *  - Configurable speech-hold timer for natural pauses
 *  - Acoustic Echo Cancellation (AEC) support
 */
class VadAudioRecorder(
    private val sampleRate: Int = 16000,
    private val speechHoldMs: Long = 600L,             // Hold speech open for 600ms after last sound
    private val preRollMs: Int = 300,                  // Pre-roll buffer to capture utterance onset
    private val onSpeechStart: () -> Unit = {},
    private val onAudioReady: (ShortArray) -> Unit,
    private val onLevel: (Float) -> Unit = {}          // Provides RMS level for UI waveform
) {
    private val TAG = "VadAudioRecorder"

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(1024)

    // Pre-roll: ring buffer holding last `preRollMs` of audio
    private val preRollFrames = (sampleRate * preRollMs / 1000)
    private val preRollBuffer = ArrayDeque<Short>(preRollFrames)

    // Accumulated speech audio (pre-roll + active speech)
    private val speechBuffer = mutableListOf<Short>()

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    @Volatile
    private var isVadEnabled = true

    @Volatile
    private var isSpeechActive = false
    private var lastSoundTimeMs = 0L

    // RMS window: 50ms at 16kHz = 800 samples
    private val rmsWindowSize = sampleRate * 50 / 1000

    // Self-calibrating ambient noise floor
    @Volatile
    private var noiseFloor = 150f

    /**
     * Toggles whether the recorder processes voice activity detection.
     * When disabled, audio samples are read from the mic (keeping it active) but discarded.
     */
    fun setVadEnabled(enabled: Boolean) {
        Log.d(TAG, "setVadEnabled: $enabled")
        isVadEnabled = enabled
        if (!enabled) {
            // Reset speech active state and clear buffers
            isSpeechActive = false
            preRollBuffer.clear()
            speechBuffer.clear()
        }
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRecording) return

        // Use MIC instead of VOICE_RECOGNITION for wider compatibility
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        // Setup Acoustic Echo Canceler
        val audioSessionId = audioRecord?.audioSessionId
        if (audioSessionId != null && AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(audioSessionId)
                aec?.enabled = true
                Log.d(TAG, "AcousticEchoCanceler successfully enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AcousticEchoCanceler: ${e.message}")
            }
        }

        preRollBuffer.clear()
        speechBuffer.clear()
        isSpeechActive = false
        isRecording = true
        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val readBuffer = ShortArray(bufferSize)
            while (isActive && isRecording) {
                val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break
                if (read < 0) {
                    Log.e(TAG, "AudioRecord read error code: $read")
                    break // Stop recording thread on hardware error (no busy loop!)
                }
                if (read == 0) {
                    delay(10)
                    continue
                }

                val chunk = readBuffer.copyOfRange(0, read)
                processChunk(chunk)
            }
        }
    }

    private fun processChunk(chunk: ShortArray) {
        val rms = computeRms(chunk)
        onLevel(rms)

        // Slow Exponential Moving Average to track ambient noise floor when not speaking
        if (!isSpeechActive) {
            noiseFloor = noiseFloor * 0.98f + rms * 0.02f
        }

        // Dynamic threshold: ambient noise floor + 350f margin
        val dynamicThreshold = (noiseFloor + 350f).coerceIn(250f, 1500f)

        if (!isVadEnabled) {
            // VAD is disabled (AI is thinking/speaking), discard audio
            return
        }

        val now = System.currentTimeMillis()
        val hasSpeech = rms > dynamicThreshold

        if (hasSpeech) {
            lastSoundTimeMs = now
            if (!isSpeechActive) {
                // Speech just started — flush pre-roll into speechBuffer first
                isSpeechActive = true
                speechBuffer.addAll(preRollBuffer)
                preRollBuffer.clear()
                onSpeechStart()
                Log.d(TAG, "VAD: Speech started (RMS=$rms, NoiseFloor=$noiseFloor, Threshold=$dynamicThreshold)")
            }
            speechBuffer.addAll(chunk.toList())
        } else {
            if (isSpeechActive) {
                speechBuffer.addAll(chunk.toList())
                // Check if silence has held long enough
                if (now - lastSoundTimeMs > speechHoldMs) {
                    // Speech ended — emit the complete utterance
                    val audio = speechBuffer.toShortArray()
                    speechBuffer.clear()
                    isSpeechActive = false
                    Log.d(TAG, "VAD: Speech ended — ${audio.size} samples (${audio.size / (sampleRate / 1000)}ms)")
                    onAudioReady(audio)
                }
            } else {
                // Update the pre-roll ring buffer
                for (sample in chunk) {
                    if (preRollBuffer.size >= preRollFrames) preRollBuffer.removeFirst()
                    preRollBuffer.addLast(sample)
                }
            }
        }
    }

    private fun computeRms(buffer: ShortArray): Float {
        var sumSquares = 0.0
        val count = buffer.size.coerceAtMost(rmsWindowSize)
        for (i in 0 until count) {
            val s = buffer[i].toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / count).toFloat()
    }

    fun stop() {
        isRecording = false
        isSpeechActive = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            aec?.enabled = false
            aec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AEC: ${e.message}")
        }
        aec = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        audioRecord = null
        preRollBuffer.clear()
        speechBuffer.clear()
    }
}

