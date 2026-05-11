package com.example.llmapp.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GoalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGoal(goal: GoalEntity)

    @Update
    fun updateGoal(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE status = 'ACTIVE' ORDER BY priority ASC")
    fun getActiveGoals(): List<GoalEntity>

    @Query("SELECT * FROM goals")
    fun getAllGoals(): List<GoalEntity>
}
