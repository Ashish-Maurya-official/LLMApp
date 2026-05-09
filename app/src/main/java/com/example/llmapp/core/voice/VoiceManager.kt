package com.example.llmapp.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.*

/**
 * VoiceManager v2 — "Gemini Live" style continuous voice loop.
 *
 * Full pipeline:
 *  1. STT captures user speech → onSpeechResult
 *  2. LLM streams tokens in → feedToken(token)
 *  3. Sentence buffer chunks by punctuation → speaks each sentence immediately
 *  4. UtteranceProgressListener detects when TTS finishes last sentence
 *  5. Automatically re-opens the mic for the next turn
 */
class VoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onPartialResult: ((String) -> Unit)? = null,
    private val onError: (String) -> Unit,
    private val speechRate: Float = 0.95f,
    private val voiceName: String = ""
) : RecognitionListener, TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

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
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(this)
        } else {
            onError("Speech recognition not available on this device.")
        }
        textToSpeech = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setSpeechRate(speechRate)
            textToSpeech?.setPitch(1.0f)
            // Apply saved voice if set
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
                    // Only restart listening when the LAST sentence of the current generation is done
                    if (utteranceId == lastSpokenUtteranceId && generationDone) {
                        isSpeaking = false
                        onSpeakingStateChanged(false)
                        if (isVoiceModeActive) {
                            // Small natural pause before listening again (like a real conversation)
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
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(this)
        }
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            onListeningStateChanged(true)
        } catch (e: Exception) {
            onError("Failed to start listening: ${e.message}")
        }
    }

    fun stopListening() {
        if (!isListening) return
        speechRecognizer?.stopListening()
        isListening = false
        onListeningStateChanged(false)
    }

    // ─── TTS Streaming Pipeline ──────────────────────────────────────────────

    /**
     * Called for every token the LLM emits.
     * [done] = true signals the LLM has finished generating.
     */
    fun feedToken(token: String, done: Boolean) {
        sentenceBuffer.append(token)

        // Look for a sentence-ending punctuation to speak immediately
        val bufferText = sentenceBuffer.toString()
        val splitIndex = findSentenceBoundary(bufferText)

        if (splitIndex > 0) {
            val sentence = bufferText.substring(0, splitIndex).trim()
            sentenceBuffer.delete(0, splitIndex)
            if (sentence.isNotBlank()) speakSentence(sentence, isLast = false)
        }

        if (done) {
            generationDone = true
            // Flush whatever is left in the buffer
            val remaining = sentenceBuffer.toString().trim()
            sentenceBuffer.clear()
            if (remaining.isNotBlank()) {
                speakSentence(remaining, isLast = true)
            } else {
                // Nothing left to speak, mark the last issued utterance as the final one
                lastSpokenUtteranceId = "utt_${utteranceCounter}"
            }
        }
    }

    /** Call this before a new user message so old buffered state is cleared */
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

    /** Returns the index just after the last sentence-ending char, or -1 if none found */
    private fun findSentenceBoundary(text: String): Int {
        val endings = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n", ": ")
        var best = -1
        for (ending in endings) {
            val idx = text.lastIndexOf(ending)
            if (idx >= 0) best = maxOf(best, idx + ending.length)
        }
        return best
    }

    // ─── Interrupt (Barge-In) ────────────────────────────────────────────────

    fun interrupt() {
        textToSpeech?.stop()
        isSpeaking = false
        onSpeakingStateChanged(false)
        sentenceBuffer.clear()
        generationDone = false
        if (isVoiceModeActive) startListening()
    }

    // ─── Simple one-shot speak (for non-voice-mode use) ─────────────────────

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
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }

    // ─── RecognitionListener ─────────────────────────────────────────────────

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        isListening = false
        onListeningStateChanged(false)
    }

    override fun onError(error: Int) {
        isListening = false
        onListeningStateChanged(false)
        // In voice-loop mode, silently restart on transient errors instead of crashing the loop
        val isTransient = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                
        if (isVoiceModeActive && isTransient) {
            // If it's a structural error, destroy and recreate
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
            mainHandler.postDelayed({ startListening() }, 600)
        } else {
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                SpeechRecognizer.ERROR_NETWORK -> "Network error in recognition"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                else -> "Recognition error ($error)"
            }
            onError(msg)
        }
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            onSpeechResult(matches[0])
        } else if (isVoiceModeActive) {
            // Empty result — loop back to listening
            mainHandler.postDelayed({ startListening() }, 400)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val partial = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (!partial.isNullOrBlank()) {
            mainHandler.post { onPartialResult?.invoke(partial) }
        }
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
