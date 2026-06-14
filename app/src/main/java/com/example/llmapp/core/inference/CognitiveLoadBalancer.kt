package com.example.llmapp.core.inference

import android.os.PowerManager

enum class RoutingPath {
    REACTIVE,  // Fast, single-shot generation
    STRATEGIC  // Deep, multi-agent (Planner -> Verifier -> Synthesizer)
}

object CognitiveLoadBalancer {

    /**
     * Determines the RoutingPath (REACTIVE or STRATEGIC) for the user query.
     * Evaluates thermal state (degrades to REACTIVE if severe), query length,
     * and presence of complex keywords.
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
