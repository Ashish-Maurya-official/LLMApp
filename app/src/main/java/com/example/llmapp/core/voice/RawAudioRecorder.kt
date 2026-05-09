package com.example.llmapp.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Captures raw 16-bit PCM audio at 16kHz.
 * Includes a simple energy-based VAD (Voice Activity Detection).
 */
class RawAudioRecorder(
    private val sampleRate: Int = 16000,
    private val onAudioData: (ShortArray) -> Unit,
    private val onSilenceDetected: () -> Unit = {},
    private val onSpeechDetected: () -> Unit = {}
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    // VAD Parameters
    private var silenceThreshold = 200 // More sensitive
    private var silenceFramesThreshold = 30 // ~1.0 seconds of silence
    private var silenceCounter = 0
    private var isSpeechActive = false

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (isRecording) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(bufferSize)
            while (isActive && isRecording) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readCount > 0) {
                    val audioChunk = buffer.copyOfRange(0, readCount)
                    processVad(audioChunk)
                    onAudioData(audioChunk)
                }
            }
        }
    }

    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processVad(buffer: ShortArray) {
        // Calculate root mean square or simple max amplitude for VAD
        var maxAmplitude = 0
        for (sample in buffer) {
            val absSample = abs(sample.toInt())
            if (absSample > maxAmplitude) maxAmplitude = absSample
        }

        if (maxAmplitude > silenceThreshold) {
            if (!isSpeechActive) {
                isSpeechActive = true
                onSpeechDetected()
            }
            silenceCounter = 0
        } else {
            silenceCounter++
            if (isSpeechActive && silenceCounter > silenceFramesThreshold) {
                isSpeechActive = false
                onSilenceDetected()
            }
        }
    }
}
