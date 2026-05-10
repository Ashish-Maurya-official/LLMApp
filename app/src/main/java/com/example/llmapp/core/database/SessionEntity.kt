package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val summary: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
