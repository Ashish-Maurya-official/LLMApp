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
    fun determineRoutingPath(query: String, thermalStatus: Int): RoutingPath {
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return RoutingPath.REACTIVE // Forced degradation
        }

        val complexKeywords = listOf("why", "how", "explain", "code", "build", "create", "plan", "debug")
        val isComplex = complexKeywords.any { query.contains(it, ignoreCase = true) }

        if (query.length > 100 || isComplex) {
            return RoutingPath.STRATEGIC
        }

        return RoutingPath.REACTIVE
    }
}
