package com.example.llmapp.core.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface VoiceEvent {
    data class PartialTranscript(val text: String) : VoiceEvent
    data class FinalTranscript(val text: String) : VoiceEvent
    object UserStartedSpeaking : VoiceEvent
    object UserStoppedSpeaking : VoiceEvent
    object AIStartedSpeaking : VoiceEvent
    object AIFinishedSpeaking : VoiceEvent
    data class Error(val message: String) : VoiceEvent
    data class PauseDetected(val durationMs: Long) : VoiceEvent
    data class SpeculativeTrigger(val partialText: String) : VoiceEvent
}

class VoiceInteractionManager(
    private val context: Context,
    private val scope: CoroutineScope
) : RecognitionListener, TextToSpeech.OnInitListener {

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceEvent> = _events

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    
    private var isListening = false
    private var isAiSpeaking = false
    
    // Sub-vocalization buffer for TTS
    private val ttsBuffer = java.lang.StringBuilder()
    private val SENTENCE_DELIMITERS = Regex("(?<=[.!?])\\s+|(?<=[.!?])\$")
    private var ttsJob: Job? = null
    
    // Advanced Voice Heuristics
    private var lastPartialResultTime = 0L
    private var speculativeFiredForCurrentTurn = false
    private var silenceMonitorJob: Job? = null

    init {
        scope.launch(Dispatchers.Main) {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(this@VoiceInteractionManager)
                }
            } else {
                _events.emit(VoiceEvent.Error("Speech Recognition not available on this device."))
            }
            
            textToSpeech = TextToSpeech(context, this@VoiceInteractionManager)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isAiSpeaking = true
                    scope.launch { _events.emit(VoiceEvent.AIStartedSpeaking) }
                }
                override fun onDone(utteranceId: String?) {
                    isAiSpeaking = false
                    scope.launch { _events.emit(VoiceEvent.AIFinishedSpeaking) }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    fun startListening() {
        if (isListening) return
        scope.launch(Dispatchers.Main) {
            isListening = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    fun stopListening() {
        scope.launch(Dispatchers.Main) {
            isListening = false
            speechRecognizer?.stopListening()
        }
    }

    fun cancelListening() {
        scope.launch(Dispatchers.Main) {
            isListening = false
            speechRecognizer?.cancel()
        }
    }

    /**
     * Feeds the streaming tokens into the SubVocalizationBuffer.
     * When a sentence boundary is hit, it flushes the chunk to the TTS engine.
     */
    fun onNewLlmToken(token: String, isDone: Boolean) {
        ttsBuffer.append(token)
        val currentText = ttsBuffer.toString()
        
        val parts = currentText.split(SENTENCE_DELIMITERS)
        if (parts.size > 1) {
            // We have at least one complete sentence
            val sentenceToSpeak = parts.dropLast(1).joinToString(" ").trim()
            if (sentenceToSpeak.isNotBlank()) {
                speakChunk(sentenceToSpeak)
            }
            // Keep the remainder in the buffer
            ttsBuffer.clear()
            ttsBuffer.append(parts.last())
        }

        if (isDone) {
            val remainder = ttsBuffer.toString().trim()
            if (remainder.isNotBlank()) {
                speakChunk(remainder)
            }
            ttsBuffer.clear()
        }
    }

    private fun speakChunk(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_ADD, null, java.util.UUID.randomUUID().toString())
    }

    fun stopSpeaking() {
        ttsBuffer.clear()
        textToSpeech?.stop()
        isAiSpeaking = false
        scope.launch { _events.emit(VoiceEvent.AIFinishedSpeaking) }
    }

    // --- RecognitionListener Implementation ---
    override fun onReadyForSpeech(params: Bundle?) {}
    
    override fun onBeginningOfSpeech() {
        scope.launch { _events.emit(VoiceEvent.UserStartedSpeaking) }
        
        lastPartialResultTime = System.currentTimeMillis()
        speculativeFiredForCurrentTurn = false
        startSilenceMonitor()
        
        // --- BARGE-IN INTERRUPTION ---
        // If the user starts talking while the AI is speaking, cut the AI off immediately.
        if (isAiSpeaking) {
            Log.w("VoiceInteractionManager", "BARGE-IN DETECTED: Cutting off AI TTS")
            stopSpeaking()
        }
    }
    
    private fun startSilenceMonitor() {
        silenceMonitorJob?.cancel()
        silenceMonitorJob = scope.launch(Dispatchers.Default) {
            while (isListening) {
                delay(500)
                val now = System.currentTimeMillis()
                if (lastPartialResultTime > 0 && now - lastPartialResultTime > 1500) { // 1.5s pause
                    _events.emit(VoiceEvent.PauseDetected(now - lastPartialResultTime))
                }
            }
        }
    }
    
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        silenceMonitorJob?.cancel()
        scope.launch { _events.emit(VoiceEvent.UserStoppedSpeaking) }
    }
    
    override fun onError(error: Int) {
        silenceMonitorJob?.cancel()
        // Conversational Recovery Strategy: Auto-restart listening for transient errors
        val isTransient = error == SpeechRecognizer.ERROR_NO_MATCH || 
                          error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || 
                          error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        
        if (isListening && isTransient) {
            Log.d("VoiceInteractionManager", "ASR Transient Error (\$error). Auto-recovering...")
            scope.launch(Dispatchers.Main) {
                delay(200)
                if (isListening) startListening()
            }
        } else if (error != SpeechRecognizer.ERROR_CLIENT) {
            Log.e("VoiceInteractionManager", "ASR Fatal Error: \$error")
            scope.launch { _events.emit(VoiceEvent.Error("ASR Error: \$error")) }
        }
    }
    
    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val finalTranscript = matches[0]
            scope.launch { _events.emit(VoiceEvent.FinalTranscript(finalTranscript)) }
        }
        
        // Continuous listening restart
        if (isListening) {
            scope.launch(Dispatchers.Main) {
                delay(300)
                startListening()
            }
        }
    }
    
    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val partial = matches[0]
            lastPartialResultTime = System.currentTimeMillis()
            scope.launch { _events.emit(VoiceEvent.PartialTranscript(partial)) }
            
            // --- SPECULATIVE COGNITION TRIGGER ---
            // If the user has spoken more than 5 words, fire a speculative intent
            val wordCount = partial.split(" ").size
            if (wordCount > 5 && !speculativeFiredForCurrentTurn) {
                speculativeFiredForCurrentTurn = true
                scope.launch { _events.emit(VoiceEvent.SpeculativeTrigger(partial)) }
            }
        }
    }
    
    override fun onEvent(eventType: Int, params: Bundle?) {}

    fun destroy() {
        speechRecognizer?.destroy()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
    }
}
