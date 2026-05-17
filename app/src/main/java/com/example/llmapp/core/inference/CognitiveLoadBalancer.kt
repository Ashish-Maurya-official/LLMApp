package com.example.llmapp.core.inference

import android.os.PowerManager

enum class RoutingPath {
    REACTIVE,  // Fast, single-shot generation
    STRATEGIC  // Deep, multi-agent (Planner -> Verifier -> Synthesizer)
}

object CognitiveLoadBalancer {

    /**
     * Determines whether the user's query requires deep thought or just a fast response.
     * 
     * Heuristics:
     * 1. If Thermal Status is SEVERE or higher, FORCE Reactive to save battery/hardware.
     * 2. If query > 100 chars, it's probably complex -> Strategic.
     * 3. If query contains "why", "how", "explain", "code", "build" -> Strategic.
     * 
     * By using heuristics instead of an LLM classification call, we save hundreds of milliseconds
     * and preserve battery life.
     */
    fun determineRoutingPath(rawQuery: String, thermalStatus: Int): RoutingPath {
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return RoutingPath.REACTIVE // Forced degradation
        }

        val complexKeywords = listOf("build an app", "plan a trip", "write a script", "debug this", "create a plan")
        val isComplex = complexKeywords.any { rawQuery.contains(it, ignoreCase = true) }

        if (rawQuery.length > 250 || isComplex) {
            return RoutingPath.STRATEGIC
        }

        return RoutingPath.REACTIVE
    }
}
