package com.example.llmapp.core.voice.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android TextToSpeech wrapped behind the TtsEngine interface.
 * Used as the fallback when PiperVoiceEngine is unavailable.
 */
class FallbackTtsEngine(
    context: Context,
    private val speechRate: Float = 0.92f,
    private val pitch: Float = 1.0f,
    private val locale: Locale = Locale.US
) : TtsEngine {

    private val TAG = "FallbackTtsEngine"
    private var tts: TextToSpeech? = null
    private var isReady = false
    private var currentUtteranceId: String? = null

    // Completion tracking via coroutine continuation
    private var pendingContinuation: CancellableContinuation<Unit>? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = locale
                tts?.setSpeechRate(speechRate)
                tts?.setPitch(pitch)
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == currentUtteranceId) {
                            safeResume()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        safeResume()
                    }
                })
                isReady = true
                Log.i(TAG, "Android TTS initialized (fallback)")
            } else {
                Log.e(TAG, "Android TTS initialization failed: $status")
            }
        }
    }

    private fun safeResume() {
        val cont = pendingContinuation
        pendingContinuation = null
        if (cont?.isActive == true) {
            cont.resume(Unit)
        }
    }

    override fun isAvailable(): Boolean = isReady && tts != null

    override suspend fun speak(text: String, onStart: () -> Unit, onDone: () -> Unit) {
        if (!isAvailable() || text.isBlank()) { onDone(); return }

        onStart()

        suspendCancellableCoroutine<Unit> { cont ->
            pendingContinuation = cont
            currentUtteranceId = UUID.randomUUID().toString()
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId)
            if (result == TextToSpeech.ERROR) {
                pendingContinuation = null
                cont.resume(Unit)
            }
        }

        onDone()
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) { /* ignore */ }
        safeResume()
    }

    override fun destroy() {
        stop()
        try {
            tts?.shutdown()
        } catch (e: Exception) { /* ignore */ }
        tts = null
        isReady = false
    }
}
