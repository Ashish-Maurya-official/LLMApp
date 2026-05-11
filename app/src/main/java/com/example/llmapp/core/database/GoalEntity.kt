package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val description: String,
    val status: String, // "ACTIVE", "COMPLETED", "SUSPENDED"
    val priority: Int, // 1 = HIGH, 2 = MEDIUM, 3 = LOW
    val timestamp: Long = System.currentTimeMillis()
)
