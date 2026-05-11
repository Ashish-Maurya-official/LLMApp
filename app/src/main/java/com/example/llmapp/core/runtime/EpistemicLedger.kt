package com.example.llmapp.core.runtime

import com.example.llmapp.core.database.MemoryEntity
import java.security.MessageDigest

object EpistemicLedger {

    /**
     * Calculates a deterministic SHA-256 hash of the current cognitive state.
     * By hashing the sorted IDs, trust zones, and content hashes of recent memories, 
     * we can verify if the database was externally tampered with or if a transaction 
     * rolled back partially.
     */
    fun calculateStateHash(memories: List<MemoryEntity>): String {
        if (memories.isEmpty()) return "empty_state"
        
        val digest = MessageDigest.getInstance("SHA-256")
        val sortedMemories = memories.sortedBy { it.id }
        
        // Build a deterministic string representing the memory snapshot
        val stateString = sortedMemories.joinToString(separator = "|") { 
            "${it.id}:${it.trustZone}:${it.content.hashCode()}"
        }
        
        val hashBytes = digest.digest(stateString.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
