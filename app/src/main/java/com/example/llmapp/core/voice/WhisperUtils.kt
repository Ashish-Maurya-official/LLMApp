package com.example.llmapp.core.voice

import kotlin.math.*

/**
 * Utilities for Whisper Audio Processing (STT)
 */
object WhisperUtils {
    private const val SAMPLE_RATE = 16000
    private const val N_FFT = 400
    private const val HOP_LENGTH = 160
    private const val N_MELS = 80

    /**
     * Calculates the Log-Mel Spectrogram required for Whisper input.
     * Expects 16-bit PCM normalized to [-1, 1].
     */
    fun calculateMelSpectrogram(samples: FloatArray): Array<FloatArray> {
        val nFrames = 3000
        val melSpectrogram = Array(N_MELS) { FloatArray(nFrames) }

        // Hamming Window
        val window = FloatArray(N_FFT) { 
            0.54f - 0.46f * cos(2 * PI * it / (N_FFT - 1)).toFloat() 
        }

        for (i in 0 until nFrames) {
            val start = i * HOP_LENGTH
            if (start + N_FFT > samples.size) break

            val frame = FloatArray(N_FFT) { samples[start + it] * window[it] }
            val fft = computeFFT(frame)
            val powerSpectrum = FloatArray(N_FFT / 2 + 1) { 
                fft[it].re * fft[it].re + fft[it].im * fft[it].im 
            }

            applyMelFilterbank(powerSpectrum, melSpectrogram, i)
        }
        
        // Log scaling
        for (m in 0 until N_MELS) {
            for (t in 0 until nFrames) {
                melSpectrogram[m][t] = log10(max(1e-10f, melSpectrogram[m][t]))
            }
        }
        return melSpectrogram
    }

    private data class Complex(val re: Float, val im: Float)

    private fun computeFFT(input: FloatArray): Array<Complex> {
        // Simplified Cooley-Tukey FFT implementation
        val n = input.size
        if (n == 1) return arrayOf(Complex(input[0], 0f))
        
        val evenInput = FloatArray(n / 2) { input[it * 2] }
        val oddInput = FloatArray(n / 2) { input[it * 2 + 1] }
        
        val even = computeFFT(evenInput)
        val odd = computeFFT(oddInput)
        
        val result = Array(n) { Complex(0f, 0f) }
        for (k in 0 until n / 2) {
            val angle = -2 * PI * k / n
            val w = Complex(cos(angle).toFloat(), sin(angle).toFloat())
            val wkOdd = Complex(w.re * odd[k].re - w.im * odd[k].im, w.re * odd[k].im + w.im * odd[k].re)
            result[k] = Complex(even[k].re + wkOdd.re, even[k].im + wkOdd.im)
            result[k + n / 2] = Complex(even[k].re - wkOdd.re, even[k].im - wkOdd.im)
        }
        return result
    }

    private fun applyMelFilterbank(powerSpectrum: FloatArray, melSpectrogram: Array<FloatArray>, frameIndex: Int) {
        // Frequency to Mel conversion: m = 2595 * log10(1 + f / 700)
        // Mel to Frequency conversion: f = 700 * (10^(m / 2595) - 1)
        
        val minFreq = 0.0
        val maxFreq = SAMPLE_RATE / 2.0
        val minMel = 2595 * log10(1 + minFreq / 700)
        val maxMel = 2595 * log10(1 + maxFreq / 700)
        
        // Compute bin centers in Mel scale and then in Frequency scale
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            minMel + i * (maxMel - minMel) / (N_MELS + 1)
        }
        val freqPoints = DoubleArray(N_MELS + 2) { i ->
            700 * (10.0.pow(melPoints[i] / 2595.0) - 1.0)
        }
        
        // Convert frequencies to FFT bin indices
        val bins = IntArray(N_MELS + 2) { i ->
            (floor((N_FFT + 1) * freqPoints[i] / SAMPLE_RATE)).toInt()
        }
        
        for (m in 1..N_MELS) {
            var filterValue = 0.0f
            for (k in bins[m - 1] until bins[m]) {
                filterValue += powerSpectrum[k] * (k - bins[m - 1]).toFloat() / (bins[m] - bins[m - 1]).toFloat()
            }
            for (k in bins[m] until bins[m + 1]) {
                filterValue += powerSpectrum[k] * (bins[m + 1] - k).toFloat() / (bins[m + 1] - bins[m]).toFloat()
            }
            melSpectrogram[m - 1][frameIndex] = filterValue
        }
    }
}
