package com.example.llmapp.ui.settings.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.runtime.*
import java.util.Locale

data class TtsState(
    val isReady: Boolean,
    val availableVoices: List<Voice>
)

@Composable
fun rememberTtsVoices(context: Context): TtsState {
    var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var ttsReady by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                val voices: Set<Voice>? = tts?.voices
                if (voices != null) {
                    val defaultLang = Locale.getDefault().language
                    availableVoices = voices
                        .filter { v: Voice -> v.locale.language == defaultLang || v.locale == Locale.US }
                        .sortedBy { v: Voice -> v.name }
                }
            }
        }
        onDispose { tts?.shutdown() }
    }

    return TtsState(ttsReady, availableVoices)
}
