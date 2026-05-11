package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String?,
    val type: String, // e.g., "semantic", "episodic", "profile"
    val content: String,
    val embedding: ByteArray? = null,
    val trustZone: Int = 2, // 0=Identity, 1=User Explicit, 2=Inferred, 3=External
    val epistemicState: String = "ASSUMED", // VERIFIED, PROBABLE, ASSUMED, CONTRADICTED
    val lineageId: String? = null, // GenerationSessionId that inferred this trait
    val lastAccessed: Long = System.currentTimeMillis(),
    val accessCount: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MemoryEntity

        if (id != other.id) return false
        if (sessionId != other.sessionId) return false
        if (type != other.type) return false
        if (content != other.content) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        if (trustZone != other.trustZone) return false
        if (epistemicState != other.epistemicState) return false
        if (lineageId != other.lineageId) return false
        if (lastAccessed != other.lastAccessed) return false
        if (accessCount != other.accessCount) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (sessionId?.hashCode() ?: 0)
        result = 31 * result + type.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + trustZone
        result = 31 * result + epistemicState.hashCode()
        result = 31 * result + (lineageId?.hashCode() ?: 0)
        result = 31 * result + lastAccessed.hashCode()
        result = 31 * result + accessCount
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
