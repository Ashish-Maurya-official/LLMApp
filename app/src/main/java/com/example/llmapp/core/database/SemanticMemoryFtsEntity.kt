package com.example.llmapp.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

/**
 * FTS4 index for semantic_memories to enable full-text keyword search.
 */
@Entity(tableName = "semantic_memories_fts")
@Fts4(contentEntity = SemanticMemoryEntity::class)
data class SemanticMemoryFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long,
    val content: String
)
