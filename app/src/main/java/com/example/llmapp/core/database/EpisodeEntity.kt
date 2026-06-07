package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Episode: A summary of a conversation segment (20 turns or 30 minutes).
 * Hierarchical: Episode → Chapter → Book for long-term scaling.
 */
@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val topic: String,               // "LiteRT GPU debugging"
    val outcome: String,             // "OpenCL probe falsely blocked GPU delegate"
    val keyFacts: String,            // JSON array of key facts
    val turnCount: Int,
    val chapterId: Long? = null,     // FK → chapters (null = unassigned)
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Chapter: Groups related episodes into a coherent narrative.
 * e.g., "LiteRT Development", "Memory System Design"
 */
@Entity(tableName = "chapters")
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,               // "LiteRT Development"
    val bookId: Long? = null,        // FK → books (null = unassigned)
    val episodeCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Book: Top-level grouping of chapters.
 * e.g., "AI Assistant Project", "Personal Notes"
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,               // "AI Assistant Project"
    val chapterCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
