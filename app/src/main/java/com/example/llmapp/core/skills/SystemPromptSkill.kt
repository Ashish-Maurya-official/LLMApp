package com.example.llmapp.core.skills

class SystemPromptSkill : Skill {
    override val name = "ThinkingMode"
    override val description = "Enables internal reasoning before generating the final response. Use this when the problem requires step-by-step logic."

    override fun execute(args: Map<String, Any>): String {
        val reasoning = args["reasoning"] as? String ?: return "Error: No reasoning provided."
        // In a real implementation, this would trigger a UI state change to show "Thinking..."
        // and append the reasoning to the context window seamlessly.
        return "Reasoning completed: $reasoning"
    }
}
