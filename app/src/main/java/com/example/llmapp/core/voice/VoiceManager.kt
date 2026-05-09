package com.example.llmapp.core.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.*

/**
 * VoiceManager v3 — Custom Whisper STT + Android TTS.
 */
class VoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onPartialResult: ((String) -> Unit)? = null,
    private val onInterrupted: () -> Unit = {},
    private val onError: (String) -> Unit,
    private val speechRate: Float = 0.95f,
    private val voiceName: String = "",
    private val language: String = "English"
) : RecognitionListener, TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    
    // Custom STT Components
    private var rawAudioRecorder: RawAudioRecorder? = null
    private var whisperEngine: WhisperSttEngine? = null
    private val audioBuffer = mutableListOf<Short>()
    private val sttScope = CoroutineScope(Dispatchers.Main + Job())

    private fun getLocaleForLanguage(): Locale {
        return when (language.lowercase(Locale.getDefault())) {
            "hindi" -> Locale("hi", "IN")
            "bhojpuri" -> Locale("bho", "IN")
            else -> Locale.US
        }
    }

    // Voice mode loop controls
    var isVoiceModeActive = false
    private var isListening = false
    private var isSpeaking = false
    private var generationDone = false

    // Sentence streaming buffer
    private val sentenceBuffer = StringBuilder()
    private var utteranceCounter = 0
    private var lastSpokenUtteranceId = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        whisperEngine = WhisperSttEngine(context)
        rawAudioRecorder = RawAudioRecorder(
            onAudioData = { data -> audioBuffer.addAll(data.toList()) },
            onSilenceDetected = { 
                mainHandler.post {
                    stopListening()
                    processWhisper() 
                }
            },
            onSpeechDetected = { /* pulse UI? */ }
        )
        textToSpeech = TextToSpeech(context, this)
    }

    private fun processWhisper() {
        val data = audioBuffer.toShortArray()
        audioBuffer.clear()
        if (data.isNotEmpty()) {
            whisperEngine?.transcribe(data) { text ->
                mainHandler.post { onSpeechResult(text) }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .build()
            textToSpeech?.setAudioAttributes(audioAttributes)
            
            textToSpeech?.language = getLocaleForLanguage()
            textToSpeech?.setSpeechRate(speechRate)
            textToSpeech?.setPitch(1.0f)
            
            if (voiceName.isNotBlank()) {
                val voice = textToSpeech?.voices?.find { it.name == voiceName }
                if (voice != null) textToSpeech?.voice = voice
            }
            setupUtteranceListener()
        } else {
            onError("TTS initialization failed.")
        }
    }

    private fun setupUtteranceListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    isSpeaking = true
                    onSpeakingStateChanged(true)
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (utteranceId == lastSpokenUtteranceId && generationDone) {
                        isSpeaking = false
                        onSpeakingStateChanged(false)
                        if (isVoiceModeActive) {
                            mainHandler.postDelayed({ startListening() }, 400)
                        }
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    isSpeaking = false
                    onSpeakingStateChanged(false)
                }
            }
        })
    }

    // ─── STT ────────────────────────────────────────────────────────────────

    fun startListening() {
        if (isListening || isSpeaking) return
        isListening = true
        onListeningStateChanged(true)
        rawAudioRecorder?.start(sttScope)
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        onListeningStateChanged(false)
        rawAudioRecorder?.stop()
    }

    // ─── TTS Streaming Pipeline ──────────────────────────────────────────────

    fun feedToken(token: String, done: Boolean) {
        sentenceBuffer.append(token)
        val bufferText = sentenceBuffer.toString()
        val splitIndex = findSentenceBoundary(bufferText)

        if (splitIndex > 0) {
            val sentence = bufferText.substring(0, splitIndex).trim()
            sentenceBuffer.delete(0, splitIndex)
            if (sentence.isNotBlank()) speakSentence(sentence, isLast = false)
        }

        if (done) {
            generationDone = true
            val remaining = sentenceBuffer.toString().trim()
            sentenceBuffer.clear()
            if (remaining.isNotBlank()) {
                speakSentence(remaining, isLast = true)
            } else {
                lastSpokenUtteranceId = "utt_${utteranceCounter}"
            }
        }
    }

    fun resetForNewGeneration() {
        generationDone = false
        sentenceBuffer.clear()
        utteranceCounter = 0
        lastSpokenUtteranceId = ""
    }

    private fun speakSentence(text: String, isLast: Boolean) {
        utteranceCounter++
        val utteranceId = "utt_$utteranceCounter"
        if (isLast) lastSpokenUtteranceId = utteranceId
        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun findSentenceBoundary(text: String): Int {
        val endings = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n", ": ")
        var best = -1
        for (ending in endings) {
            val idx = text.lastIndexOf(ending)
            if (idx >= 0) best = maxOf(best, idx + ending.length)
        }
        return best
    }

    fun interrupt() {
        textToSpeech?.stop()
        isSpeaking = false
        onSpeakingStateChanged(false)
        sentenceBuffer.clear()
        generationDone = false
        if (isVoiceModeActive) startListening()
    }

    fun speak(text: String) {
        utteranceCounter++
        val utteranceId = "utt_$utteranceCounter"
        lastSpokenUtteranceId = utteranceId
        generationDone = true
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stopSpeaking() {
        textToSpeech?.stop()
        isSpeaking = false
        onSpeakingStateChanged(false)
    }

    fun destroy() {
        isVoiceModeActive = false
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        rawAudioRecorder?.stop()
    }

    // ─── RecognitionListener (Legacy/Unused) ────────────────────────────────

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) {}
    override fun onResults(results: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
