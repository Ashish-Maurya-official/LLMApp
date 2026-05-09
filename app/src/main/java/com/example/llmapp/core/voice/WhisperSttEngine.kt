package com.example.llmapp.core.voice

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Custom Whisper STT Engine using TFLite.
 * Note: Requires a 'whisper.tflite' model in the assets folder.
 */
class WhisperSttEngine(private val context: Context) {
    private var interpreter: Interpreter? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        try {
            val model = FileUtil.loadMappedFile(context, "whisper-tiny.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Use GPU if available
                // addDelegate(GpuDelegate()) 
            }
            interpreter = Interpreter(model, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun transcribe(audioData: ShortArray, callback: (String) -> Unit) {
        executor.execute {
            if (interpreter == null) {
                callback("Error: Whisper model not loaded.")
                return@execute
            }

            // 1. Pre-processing: Convert ShortArray (PCM) to FloatArray normalized to [-1, 1]
            val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768f }

            // 2. Feature Extraction: Calculate Log-Mel Spectrogram
            val mel = WhisperUtils.calculateMelSpectrogram(floatAudio)
            
            // Whisper TFLite expects (1, 80, 3000) or (80, 3000)
            val inputBuffer = ByteBuffer.allocateDirect(80 * 3000 * 4).apply {
                order(ByteOrder.nativeOrder())
                for (m in 0 until 80) {
                    for (t in 0 until 3000) {
                        putFloat(mel[m][t])
                    }
                }
            }

            // 3. Inference
            // Whisper TFLite typically has multiple outputs: [text_tokens, ...]
            // This structure varies by model version.
            try {
                // Determine output shape and type
                val outputMap = mutableMapOf<Int, Any>()
                val tokenOutput = Array(1) { IntArray(200) }
                outputMap[0] = tokenOutput
                
                interpreter?.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
                
                val resultText = decodeTokens(tokenOutput[0])
                callback(resultText)
            } catch (e: Exception) {
                callback("Inference failed: ${e.message}")
            }
        }
    }

    private val decoder = WhisperDecoder()

    private fun decodeTokens(tokens: IntArray): String {
        return decoder.decode(tokens)
    }

    fun close() {
        interpreter?.close()
        executor.shutdown()
    }
}
