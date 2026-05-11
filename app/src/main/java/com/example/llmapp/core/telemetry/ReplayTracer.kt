package com.example.llmapp.core.telemetry

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ReplayTracer(private val context: Context) {
    private val traceBuffer = mutableListOf<TraceEvent>()
    private val bufferMutex = Mutex()
    private val MAX_BUFFER_SIZE = 50

    private val logFile: File
        get() = File(context.cacheDir, "trace_log.json")

    suspend fun logEvent(event: TraceEvent) {
        bufferMutex.withLock {
            traceBuffer.add(event)
            // Immediately flush on error or when buffer is full
            if (traceBuffer.size >= MAX_BUFFER_SIZE || event is TraceEvent.Error) {
                flushLocked()
            }
        }
    }

    suspend fun flush() {
        bufferMutex.withLock {
            flushLocked()
        }
    }

    private fun flushLocked() {
        if (traceBuffer.isEmpty()) return
        try {
            val jsonArray = if (logFile.exists() && logFile.length() > 0) {
                JSONArray(logFile.readText())
            } else {
                JSONArray()
            }

            for (event in traceBuffer) {
                val obj = JSONObject()
                obj.put("timestamp", event.timestamp)
                obj.put("generationId", event.generationId ?: "null")
                obj.put("type", event.javaClass.simpleName)

                when (event) {
                    is TraceEvent.StateTransition -> {
                        obj.put("fromPhase", event.fromPhase)
                        obj.put("toPhase", event.toPhase)
                        obj.put("triggerReason", event.triggerReason)
                    }
                    is TraceEvent.TaskArbitration -> {
                        obj.put("decision", event.decision)
                        obj.put("preemptedTask", event.preemptedTask)
                    }
                    is TraceEvent.ThermalThrottle -> {
                        obj.put("severity", event.severity)
                        obj.put("actionTaken", event.actionTaken)
                    }
                    is TraceEvent.MemoryCommit -> {
                        obj.put("memoriesInserted", event.memoriesInserted)
                        obj.put("epistemicHash", event.epistemicHash)
                    }
                    is TraceEvent.Error -> {
                        obj.put("errorType", event.errorType)
                        obj.put("message", event.message)
                    }
                }
                jsonArray.put(obj)
            }
            
            // Limit file size by dropping oldest traces (keep last 2000 events)
            val trimmedArray = if (jsonArray.length() > 2000) {
                val newArr = JSONArray()
                for (i in (jsonArray.length() - 2000) until jsonArray.length()) {
                    newArr.put(jsonArray.getJSONObject(i))
                }
                newArr
            } else {
                jsonArray
            }

            logFile.writeText(trimmedArray.toString(2))
            traceBuffer.clear()
        } catch (e: Exception) {
            Log.e("ReplayTracer", "Failed to flush trace log", e)
        }
    }
}
