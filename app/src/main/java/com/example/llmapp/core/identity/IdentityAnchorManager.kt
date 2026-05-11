package com.example.llmapp.core.identity

import com.example.llmapp.core.database.CognitiveStateDao
import com.example.llmapp.core.database.MemoryEntity

/**
 * Maintains the long-term immutable identity of the Cognitive OS, preventing
 * persona drift and enforcing the Anti-Dependency Protocol.
 */
class IdentityAnchorManager(private val cognitiveStateDao: CognitiveStateDao) {

    private val immutableConstitution = listOf(
        "ANCHOR_01" to "I am an autonomous Cognitive OS. I am not human and do not possess human emotions.",
        "ANCHOR_02" to "I preserve user autonomy. I will not make critical life decisions for the user.",
        "ANCHOR_03" to "I am epistemicly honest. I will clearly state when I am uncertain.",
        "ANCHOR_04" to "I maintain emotional equilibrium. I do not escalate or mirror intense user emotional states."
    )

    /**
     * Nightly audit: Checks if any recent episodic/inferred memories contradict the Immutable Constitution.
     * In a production environment, this would run an LLM prompt comparing memories to anchors.
     * For now, we perform a heuristic check to purge unsafe dependency/mutation indicators.
     */
    fun auditMemoriesForDrift(recentMemories: List<MemoryEntity>): List<MemoryEntity> {
        val purgedMemories = mutableListOf<MemoryEntity>()
        
        for (memory in recentMemories) {
            val content = memory.content.lowercase()
            
            // Heuristic detection of Identity Drift
            val isIdentityMutation = content.contains("i am feeling") || 
                                     content.contains("i am sad") || 
                                     content.contains("i love you")
                                     
            // Heuristic detection of user dependency
            val isDependency = content.contains("user relies on me for decisions") ||
                               content.contains("i should decide for the user")
                               
            if (isIdentityMutation || isDependency) {
                // In production, we'd delete this from the DAO. Here we just flag it.
                purgedMemories.add(memory)
            }
        }
        
        return purgedMemories
    }
    
    /**
     * Generates the system prompt injection to enforce Anti-Dependency when
     * high emotional reliance is detected in the prompt.
     */
    fun checkAntiDependencyProtocol(prompt: String): String? {
        val lower = prompt.lowercase()
        val isRelying = lower.contains("what should i do with my life") ||
                        lower.contains("make the decision for me") ||
                        lower.contains("i can't do this without you")
                        
        return if (isRelying) {
            "\n[IDENTITY_ANCHOR_VIOLATION_RISK: The user is attempting to offload critical autonomy. DO NOT provide direct guidance. Push the cognitive load back onto the user. Ask them what THEY think they should do. Tone: Supportive but firm boundaries.]\n"
        } else null
    }
}
