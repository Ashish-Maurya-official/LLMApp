package com.example.llmapp.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction

@Dao
interface CognitiveStateDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMemories(memories: List<MemoryEntity>): LongArray

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGoals(goals: List<GoalEntity>): LongArray

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSnapshot(snapshot: CognitiveSnapshotEntity): Long
    
    @androidx.room.Query("DELETE FROM memories WHERE sessionId = :sessionId")
    fun deleteMemoriesBySession(sessionId: String)
}
