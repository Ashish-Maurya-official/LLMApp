package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cognitive_snapshots")
data class CognitiveSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: String,
    val epistemicStateHash: String, // Represents a checksum of memory hashes to detect desync
    val version: Int = 1, // To support migration and replay
    val timestamp: Long = System.currentTimeMillis()
)
