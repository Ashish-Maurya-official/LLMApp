package com.example.llmapp.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM sessions ORDER BY timestamp DESC")
    fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun getSession(sessionId: String): SessionEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesPagingSource(sessionId: String): PagingSource<Int, MessageEntity>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    fun deleteSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMemory(memory: MemoryEntity)

    @Query("SELECT * FROM memories JOIN memories_fts ON memories.id = memories_fts.rowid WHERE memories_fts MATCH :query LIMIT 50")
    fun searchMemoriesFts(query: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE content = :content LIMIT 1")
    fun getMemoryByExactContent(content: String): MemoryEntity?

    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccessed = :timestamp WHERE id = :memoryId")
    fun updateMemoryAccess(memoryId: Long, timestamp: Long)

    @Query("SELECT * FROM memories WHERE type = :type")
    fun getMemoriesByType(type: String): List<MemoryEntity>
    
    @Query("DELETE FROM memories")
    fun clearMemories()
}
