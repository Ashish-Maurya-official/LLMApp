package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Semantic memory: learned facts extracted from conversations.
 * e.g., "User has a cat named Luna", "User is working on an AI assistant project"
 */
@Entity(tableName = "semantic_memories")
data class SemanticMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,             // The fact itself
    val importanceScore: Float = 0.5f,
    val epistemicState: String = "ASSUMED", // VERIFIED, PROBABLE, ASSUMED, CONTRADICTED
    val embedding: ByteArray? = null, // Future: vector embeddings
    val accessCount: Int = 1,
    val lastAccessed: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val lineageId: String? = null    // Session that created this
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SemanticMemoryEntity
        return id == other.id && content == other.content
    }

    override fun hashCode(): Int = 31 * id.hashCode() + content.hashCode()
}
