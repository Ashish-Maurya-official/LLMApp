package com.example.llmapp.core.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.*
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer

/**
 * Custom High-Fidelity TTS Engine using Piper (ONNX).
 * Note: Requires 'piper.onnx' and 'piper.json' in assets.
 */
class PiperTtsEngine(private val context: Context) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var audioTrack: AudioTrack? = null

    fun isReady(): Boolean = ortSession != null

    init {
        try {
            val modelBytes = context.assets.open("piper.onnx").readBytes()
            ortSession = ortEnv.createSession(modelBytes)
            
            // Initialize AudioTrack for 22050Hz (standard Piper sample rate)
            val minBufSize = AudioTrack.getMinBufferSize(
                22050, 
                AudioFormat.CHANNEL_OUT_MONO, 
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(22050)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(minBufSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            
            audioTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun speak(text: String, onComplete: () -> Unit = {}) {
        scope.launch {
            if (ortSession == null) return@launch
            
            try {
                // 1. Convert text to phoneme IDs
                // (Simplified: In a real app, you'd use a phonemizer. 
                // For this agent, we'll assume a simplified tokenization or ASCII fallback)
                val inputIds = text.map { it.code.toLong() }.toLongArray()
                val inputShape = longArrayOf(1, inputIds.size.toLong())
                
                val env = OrtEnvironment.getEnvironment()
                val inputTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), inputShape)
                
                // Piper also needs lengths and scales
                val lengthTensor = OnnxTensor.createTensor(env, longArrayOf(inputIds.size.toLong()))
                val noiseScale = OnnxTensor.createTensor(env, floatArrayOf(0.667f))
                val lengthScale = OnnxTensor.createTensor(env, floatArrayOf(1.0f))
                
                val inputs = mapOf(
                    "input" to inputTensor,
                    "input_lengths" to lengthTensor,
                    "sid" to OnnxTensor.createTensor(env, longArrayOf(0)) // speaker id
                )
                
                val result = ortSession?.run(inputs)
                val audioOutput = result?.get(0)?.value as? FloatArray
                
                if (audioOutput != null) {
                    // Convert FloatArray [-1, 1] to ShortArray [PCM 16-bit]
                    val pcmData = ShortArray(audioOutput.size) {
                        (audioOutput[it].coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    }
                    audioTrack?.write(pcmData, 0, pcmData.size)
                }
                
                onComplete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        audioTrack?.pause()
        audioTrack?.flush()
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
        audioTrack?.release()
        scope.cancel()
    }
}
