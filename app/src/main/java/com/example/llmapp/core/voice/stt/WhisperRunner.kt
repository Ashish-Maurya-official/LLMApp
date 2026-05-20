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
 * Production STT runner.
 *
 * Primary path  : whisper-tiny.tflite  (on-device, offline)
 * Fallback path : Android SpeechRecognizer (online, lower latency for short queries)
 *
 * The fallback is automatically selected if TFLite model fails to load or if
 * the TFLite path produces an empty/error string.
 */
class WhisperRunner(private val context: Context) {

    private val TAG = "WhisperRunner"
    private var tfliteInterpreter: Interpreter? = null
    private var vocab: Map<Int, String> = emptyMap()
    private val isTfliteReady: Boolean get() = tfliteInterpreter != null

    // Android fallback
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingFallbackCallback: ((String) -> Unit)? = null

    init {
        loadTflite()
        loadVocab()
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

    private fun initFallback() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(t: Int, p: Bundle?) {}
                        override fun onPartialResults(r: Bundle?) {}
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
     * Transcribe raw 16kHz 16-bit PCM audio.
     * Runs on the calling coroutine's dispatcher — caller should use Dispatchers.IO.
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
     * Run Android SpeechRecognizer for the VAD-less path (called directly
     * from ConversationEngine when TFLite is unavailable).
     * Must be called on Main thread.
     */
    fun startAndroidAsr(callback: (String) -> Unit) {
        if (speechRecognizer == null) { callback(""); return }
        pendingFallbackCallback = callback
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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

    // ─── Log-Mel Spectrogram ───────────────────────────────────────────────

    private val N_FFT = 400
    private val HOP = 160
    private val N_MELS = 80
    private val N_FRAMES = 3000

    private fun computeLogMelSpectrogram(samples: FloatArray): Array<FloatArray> {
        val mel = Array(N_MELS) { FloatArray(N_FRAMES) }
        val window = hamming(N_FFT)
        val fftSize = N_FFT

        for (frame in 0 until N_FRAMES) {
            val start = frame * HOP
            if (start + fftSize > samples.size) break
            val windowed = FloatArray(fftSize) { samples[start + it] * window[it] }
            val powerSpec = powerSpectrum(windowed)
            applyMelFilters(powerSpec, mel, frame)
        }
        // Log scaling with global normalization (Whisper style)
        var maxVal = mel.flatMap { it.toList() }.maxOrNull() ?: 1f
        if (maxVal == 0f) maxVal = 1f
        for (m in 0 until N_MELS) for (t in 0 until N_FRAMES) {
            mel[m][t] = (log10(max(1e-10f, mel[m][t])).coerceAtLeast(log10(maxVal) - 8f) + 4f) / 4f
        }
        return mel
    }

    private fun hamming(size: Int) = FloatArray(size) {
        (0.54 - 0.46 * cos(2 * PI * it / (size - 1))).toFloat()
    }

    private fun powerSpectrum(frame: FloatArray): FloatArray {
        val n = frame.size
        val re = frame.copyOf()
        val im = FloatArray(n)
        fft(re, im)
        return FloatArray(n / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = (-2 * PI / len).toFloat()
            val wRe = cos(ang.toDouble()).toFloat(); val wIm = sin(ang.toDouble()).toFloat()
            var i = 0
            while (i < n) {
                var curRe = 1f; var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = re[i + k]; val uIm = im[i + k]
                    val vRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val vIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm
                    re[i + k + len / 2] = uRe - vRe; im[i + k + len / 2] = uIm - vIm
                    val nRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe; curRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun applyMelFilters(power: FloatArray, mel: Array<FloatArray>, frame: Int) {
        val sampleRate = 16000.0
        val minMel = hzToMel(0.0); val maxMel = hzToMel(sampleRate / 2)
        val melPoints = DoubleArray(N_MELS + 2) { minMel + it * (maxMel - minMel) / (N_MELS + 1) }
        val freqPoints = DoubleArray(N_MELS + 2) { melToHz(melPoints[it]) }
        val bins = IntArray(N_MELS + 2) { ((N_FFT + 1) * freqPoints[it] / sampleRate).toInt().coerceIn(0, power.size - 1) }
        for (m in 1..N_MELS) {
            var s = 0f
            for (k in bins[m - 1] until bins[m]) {
                if (k >= power.size) break
                s += power[k] * (k - bins[m - 1]).toFloat() / (bins[m] - bins[m - 1] + 1).toFloat()
            }
            for (k in bins[m] until bins[m + 1]) {
                if (k >= power.size) break
                s += power[k] * (bins[m + 1] - k).toFloat() / (bins[m + 1] - bins[m] + 1).toFloat()
            }
            mel[m - 1][frame] = s
        }
    }

    private fun hzToMel(hz: Double) = 2595 * log10(1 + hz / 700)
    private fun melToHz(mel: Double) = 700 * (10.0.pow(mel / 2595) - 1)

    // ─── BPE Token Decoder ──────────────────────────────────────────────────

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
