package com.example.llmapp.core.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * Runs speech-to-text inference locally using Whisper TFLite or falls back to Android SpeechRecognizer.
 */
class WhisperRunner(private val context: Context) {

    private val TAG = "WhisperRunner"
    private var tfliteInterpreter: Interpreter? = null
    private var vocab: Map<Int, String> = emptyMap()
    private val isTfliteReady: Boolean get() = tfliteInterpreter != null


    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingFallbackCallback: ((String) -> Unit)? = null
    private var pendingPartialCallback: ((String) -> Unit)? = null
    private var pendingSpeechStartCallback: (() -> Unit)? = null

    init {
        loadTflite()
        loadVocab()
        loadMelFilters()
        initFallback()
    }

    private fun loadTflite() {
        try {
            val model = FileUtil.loadMappedFile(context, "whisper-tiny.tflite")
            val opts = Interpreter.Options().apply { setNumThreads(4) }
            tfliteInterpreter = Interpreter(model, opts)
            Log.i(TAG, "Whisper TFLite loaded successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Whisper TFLite not available, will use Android SpeechRecognizer fallback: ${e.message}")
        }
    }

    private fun loadVocab() {
        try {
            val vocabMap = mutableMapOf<Int, String>()
            context.assets.open("whisper_vocab.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line -> vocabMap[index] = line }
            }
            vocab = vocabMap
            Log.i(TAG, "Whisper vocab loaded: ${vocab.size} tokens")
        } catch (e: Exception) {
            Log.w(TAG, "Whisper vocab not available: ${e.message}")
        }
    }

    private var melFilters: FloatArray? = null

    private fun loadMelFilters() {
        try {
            val bytes = context.assets.open("mel_filters.bin").readBytes()
            val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val filters = FloatArray(80 * 201)
            byteBuffer.asFloatBuffer().get(filters)
            melFilters = filters
            Log.i(TAG, "Whisper mel filters loaded: ${filters.size} floats")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load mel_filters.bin: ${e.message}")
        }
    }

    private fun initFallback() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {
                            pendingSpeechStartCallback?.invoke()
                        }
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(t: Int, p: Bundle?) {}
                        override fun onPartialResults(r: Bundle?) {
                            val text = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                            if (text.isNotBlank()) {
                                pendingPartialCallback?.invoke(text)
                            }
                        }
                        override fun onError(error: Int) {
                            Log.e(TAG, "SpeechRecognizer error: $error")
                            pendingFallbackCallback?.invoke("")
                            pendingFallbackCallback = null
                        }
                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull() ?: ""
                            pendingFallbackCallback?.invoke(text)
                            pendingFallbackCallback = null
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Android SpeechRecognizer not available: ${e.message}")
        }
    }

    /**
     * Transcribes raw 16kHz 16-bit PCM audio.
     */
    suspend fun transcribe(audio: ShortArray): String = withContext(Dispatchers.IO) {
        if (isTfliteReady) {
            val result = runTflite(audio)
            if (result.isNotBlank() && !result.startsWith("Error")) {
                return@withContext result
            }
            Log.w(TAG, "TFLite returned empty/error, falling back to Android ASR")
        }
        // Fallback: we can't pass PCM to Android SpeechRecognizer directly,
        // so we return empty and let the caller handle via the VAD-less Android ASR path.
        return@withContext ""
    }

    /**
     * Starts Android SpeechRecognizer. Must be called on the Main thread.
     */
    fun startAndroidAsr(
        onFinal: (String) -> Unit,
        onPartial: ((String) -> Unit)? = null,
        onSpeechStart: (() -> Unit)? = null
    ) {
        if (speechRecognizer == null) { onFinal(""); return }
        pendingFallbackCallback = onFinal
        pendingPartialCallback = onPartial
        pendingSpeechStartCallback = onSpeechStart
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopAndroidAsr() {
        speechRecognizer?.stopListening()
    }

    private fun runTflite(audio: ShortArray): String {
        val interpreter = tfliteInterpreter ?: return ""
        return try {
            // Normalize PCM to [-1, 1]
            val floatAudio = FloatArray(audio.size) { audio[it] / 32768f }
            // Compute log-mel spectrogram
            val mel = computeLogMelSpectrogram(floatAudio)
            // Build input ByteBuffer (shape: [1, 80, 3000])
            val inputBuffer = ByteBuffer.allocateDirect(1 * 80 * 3000 * 4).apply {
                order(ByteOrder.nativeOrder())
                mel.forEach { row -> row.forEach { putFloat(it) } }
            }
            inputBuffer.rewind()
            val tokenOutput = Array(1) { IntArray(448) } // Whisper tiny max output
            val outputMap = mutableMapOf<Int, Any>(0 to tokenOutput)
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
            decodeTokens(tokenOutput[0])
        } catch (e: Exception) {
            Log.e(TAG, "TFLite inference failed: ${e.message}")
            ""
        }
    }

    // Log-Mel Spectrogram Computation

    private val N_FFT = 400
    private val HOP = 160
    private val N_MELS = 80
    private val N_FRAMES = 3000

    // Precompute sine and cosine tables for 400-point DFT
    private val cosTable = FloatArray(201 * N_FFT)
    private val sinTable = FloatArray(201 * N_FFT)
    
    init {
        for (k in 0 until 201) {
            for (n in 0 until N_FFT) {
                val angle = -2.0 * Math.PI * k * n / N_FFT
                cosTable[k * N_FFT + n] = Math.cos(angle).toFloat()
                sinTable[k * N_FFT + n] = Math.sin(angle).toFloat()
            }
        }
    }

    private fun computeLogMelSpectrogram(samples: FloatArray): Array<FloatArray> {
        val mel = Array(N_MELS) { FloatArray(N_FRAMES) }
        val window = hannWindow(N_FFT)

        // Center padding matching PyTorch stft(center=True)
        val pad = N_FFT / 2
        val paddedSamples = FloatArray(samples.size + pad * 2)
        System.arraycopy(samples, 0, paddedSamples, pad, samples.size)

        for (frame in 0 until N_FRAMES) {
            val start = frame * HOP
            if (start + N_FFT > paddedSamples.size) break
            val windowed = FloatArray(N_FFT) { paddedSamples[start + it] * window[it] }
            val powerSpec = powerSpectrum(windowed)
            
            // Apply exact Whisper mel filters
            for (m in 0 until N_MELS) {
                var s = 0f
                val filters = melFilters
                if (filters != null) {
                    val offset = m * 201
                    for (k in 0 until 201) {
                        s += powerSpec[k] * filters[offset + k]
                    }
                }
                mel[m][frame] = s
            }
        }
        
        // Log scaling with global normalization (Whisper style)
        var maxVal = mel.flatMap { it.toList() }.maxOrNull() ?: 1f
        if (maxVal == 0f) maxVal = 1f
        for (m in 0 until N_MELS) for (t in 0 until N_FRAMES) {
            mel[m][t] = (log10(max(1e-10f, mel[m][t])).coerceAtLeast(log10(maxVal) - 8f) + 4f) / 4f
        }
        return mel
    }

    private fun hannWindow(size: Int) = FloatArray(size) {
        (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat()
    }

    private fun powerSpectrum(frame: FloatArray): FloatArray {
        val out = FloatArray(201)
        for (k in 0 until 201) {
            var re = 0f
            var im = 0f
            val offset = k * N_FFT
            for (n in 0 until N_FFT) {
                val v = frame[n]
                re += v * cosTable[offset + n]
                im += v * sinTable[offset + n]
            }
            out[k] = re * re + im * im
        }
        return out
    }

    // BPE Token Decoder

    private fun decodeTokens(tokens: IntArray): String {
        val sb = StringBuilder()
        for (token in tokens) {
            if (token >= 50256) continue  // Whisper special tokens
            val word = vocab[token] ?: continue
            // Whisper uses Ġ for space prefix, Ċ for newline
            sb.append(word.replace("Ġ", " ").replace("Ċ", "\n"))
        }
        return sb.toString().trim()
    }

    fun destroy() {
        tfliteInterpreter?.close()
        tfliteInterpreter = null
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) { /* ignore */ }
    }
}
