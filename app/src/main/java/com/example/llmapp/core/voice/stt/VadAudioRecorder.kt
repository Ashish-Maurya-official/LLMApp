package com.example.llmapp.core.voice.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * Production-grade VAD audio recorder with:
 *  - 300ms pre-roll ring buffer (never clips the beginning of speech)
 *  - RMS-based energy detection over 50ms windows (immune to noise spikes)
 *  - Configurable speech-hold timer (default 600ms) for natural pause handling
 *  - Clean event callbacks — onSpeechStart, onAudioReady, onLevel
 */
class VadAudioRecorder(
    private val sampleRate: Int = 16000,
    private val silenceThresholdRms: Float = 250f,    // RMS amplitude threshold
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
    private var recordingJob: Job? = null
    private var isRecording = false

    private var isSpeechActive = false
    private var lastSoundTimeMs = 0L

    // RMS window: 50ms at 16kHz = 800 samples
    private val rmsWindowSize = sampleRate * 50 / 1000

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRecording) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 4
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            return
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
                if (read <= 0) continue

                val chunk = readBuffer.copyOfRange(0, read)
                processChunk(chunk)
            }
        }
    }

    private fun processChunk(chunk: ShortArray) {
        val rms = computeRms(chunk)
        onLevel(rms)

        val now = System.currentTimeMillis()
        val hasSpeech = rms > silenceThresholdRms

        if (hasSpeech) {
            lastSoundTimeMs = now
            if (!isSpeechActive) {
                // Speech just started — flush pre-roll into speechBuffer first
                isSpeechActive = true
                speechBuffer.addAll(preRollBuffer)
                preRollBuffer.clear()
                onSpeechStart()
                Log.d(TAG, "VAD: Speech started (RMS=$rms)")
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
