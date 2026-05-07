package com.example.llmapp.core.skills

interface Skill {
    val name: String
    val description: String
    
    /**
     * Executes the skill based on the arguments provided by the LLM.
     * @param args The parameters parsed from the LLM's response.
     * @return The result of the skill execution to be fed back into the LLM or UI.
     */
    fun execute(args: Map<String, Any>): String
}
