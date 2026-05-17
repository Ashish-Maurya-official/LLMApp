package com.example.llmapp.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSnapshot(snapshot: CognitiveSnapshotEntity)

    @Query("SELECT * FROM cognitive_snapshots WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    fun getLatestSnapshot(sessionId: String): CognitiveSnapshotEntity?

    @Query("SELECT * FROM cognitive_snapshots ORDER BY timestamp DESC LIMIT 1")
    fun getGlobalLatestSnapshot(): CognitiveSnapshotEntity?
}
