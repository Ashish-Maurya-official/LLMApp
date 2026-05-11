package com.example.llmapp.core.inference

import android.util.Log

object ExecutionGraph {

    /**
     * Node 1: Planner
     * Generates a step-by-step reasoning plan.
     */
    fun buildPlannerPrompt(query: String): String {
        return """
            <start_of_turn>user
            [SYSTEM: PLANNING NODE]
            You are the Planning Module of a Cognitive OS.
            The user has asked a complex query. Your job is NOT to answer it, but to write a step-by-step execution plan on how to answer it.
            
            USER QUERY: "$query"
            
            Output a markdown numbered list of steps required to gather facts, reason, and answer this query accurately.
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }

    /**
     * Node 2: Verifier
     * Critiques the plan.
     */
    fun buildVerifierPrompt(query: String, plan: String): String {
        return """
            <start_of_turn>user
            [SYSTEM: VERIFIER NODE]
            Review the following plan to answer the user's query.
            USER QUERY: "$query"
            PROPOSED PLAN:
            $plan
            
            Does this plan require any tool usage (like Web Search)? Does it hallucinate any assumptions?
            Critique the plan and output a final refined instruction set.
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }

    /**
     * Node 3: Synthesizer
     * Uses the verified plan to answer the query.
     */
    fun buildSynthesizerPrompt(query: String, context: String, verifiedPlan: String): String {
        return """
            <start_of_turn>user
            [SYSTEM: SYNTHESIZER NODE]
            Follow this verified plan to answer the user's query.
            
            VERIFIED PLAN:
            $verifiedPlan
            
            CONTEXT:
            $context
            
            USER QUERY: "$query"
            <end_of_turn>
            <start_of_turn>model
        """.trimIndent()
    }
}
