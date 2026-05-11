package com.example.llmapp.core.runtime

import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ThermalRecoveryManager(
    private val scope: CoroutineScope,
    private val emitEvent: suspend (CognitiveEvent.SystemEvent.ThermalStatusChanged) -> Unit
) {
    private var currentState = CognitiveEvent.ThermalState.NORMAL
    private var recoveryJob: Job? = null

    fun onThermalStatusChanged(status: Int) {
        val newState = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> CognitiveEvent.ThermalState.NORMAL
            PowerManager.THERMAL_STATUS_LIGHT -> CognitiveEvent.ThermalState.WARM
            PowerManager.THERMAL_STATUS_MODERATE, PowerManager.THERMAL_STATUS_SEVERE -> CognitiveEvent.ThermalState.HOT
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> CognitiveEvent.ThermalState.CRITICAL
            else -> CognitiveEvent.ThermalState.NORMAL
        }

        // Handle cooldown recovery logic: if we were hot and drop to normal, force a RECOVERY cooldown period
        if ((currentState == CognitiveEvent.ThermalState.HOT || currentState == CognitiveEvent.ThermalState.CRITICAL) && newState == CognitiveEvent.ThermalState.NORMAL) {
            enterRecovery()
            return
        }
        
        if (newState != currentState && currentState != CognitiveEvent.ThermalState.RECOVERY) {
            currentState = newState
            recoveryJob?.cancel()
            scope.launch { emitEvent(CognitiveEvent.SystemEvent.ThermalStatusChanged(newState)) }
        }
    }

    private fun enterRecovery() {
        if (currentState == CognitiveEvent.ThermalState.RECOVERY) return
        currentState = CognitiveEvent.ThermalState.RECOVERY
        scope.launch { emitEvent(CognitiveEvent.SystemEvent.ThermalStatusChanged(currentState)) }
        
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(60_000) // 60 seconds of recovery before allowing heavy queries again
            currentState = CognitiveEvent.ThermalState.NORMAL
            emitEvent(CognitiveEvent.SystemEvent.ThermalStatusChanged(currentState))
        }
    }
}
