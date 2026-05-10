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
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (sessionId?.hashCode() ?: 0)
        result = 31 * result + type.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
