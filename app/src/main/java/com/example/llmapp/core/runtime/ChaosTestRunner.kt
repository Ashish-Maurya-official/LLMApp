package com.example.llmapp.core.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Trigger via ADB:
 * adb shell am broadcast -a com.example.llmapp.CHAOS_TEST --es type "cancellation_spam"
 * adb shell am broadcast -a com.example.llmapp.CHAOS_TEST --es type "prompt_spam"
 */
class ChaosTestRunner(private val scheduler: CognitiveTaskScheduler) : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.example.llmapp.CHAOS_TEST") {
            val type = intent.getStringExtra("type") ?: "cancellation_spam"
            Log.w("ChaosTestRunner", "Starting Chaos Test: $type")
            
            when (type) {
                "cancellation_spam" -> runCancellationSpam()
                "prompt_spam" -> runPromptSpam()
                else -> Log.e("ChaosTestRunner", "Unknown chaos type: $type")
            }
        }
    }

    private fun runCancellationSpam() {
        scope.launch {
            repeat(50) { i ->
                val text = "Chaos prompt $i"
                scheduler.emit(CognitiveEvent.UIEvent.UserInput(text))
                delay((10..150).random().toLong()) // Random cancellation between 10ms and 150ms
                scheduler.emit(CognitiveEvent.RuntimeEvent.StopGeneration(scheduler.state.value.activeGenerationId ?: ""))
            }
            Log.w("ChaosTestRunner", "Cancellation spam complete.")
        }
    }

    private fun runPromptSpam() {
        scope.launch {
            repeat(20) { i ->
                scheduler.emit(CognitiveEvent.UIEvent.UserInput("Concurrent prompt $i"))
                delay(10) // Near instant to test queue depth and mutex contention
            }
            Log.w("ChaosTestRunner", "Prompt spam complete.")
        }
    }
}
