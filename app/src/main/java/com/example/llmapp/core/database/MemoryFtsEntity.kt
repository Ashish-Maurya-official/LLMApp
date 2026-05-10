package com.example.llmapp.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "memories_fts")
@Fts4(contentEntity = MemoryEntity::class)
data class MemoryFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long,
    val content: String
)
