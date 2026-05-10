package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val isUser: Boolean,
    val text: String,
    val rawContent: String,
    val thoughtsJson: String = "[]",
    val actionsJson: String = "[]",
    val timestamp: Long = System.currentTimeMillis(),
    val importanceScore: Float = 0f,
    val isVectorized: Boolean = false
)
