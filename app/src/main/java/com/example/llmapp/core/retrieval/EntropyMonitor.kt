package com.example.llmapp.core.retrieval

import com.example.llmapp.core.database.ChatDao
import com.example.llmapp.core.database.MemoryEntity

class EntropyMonitor(private val chatDao: ChatDao) {

    /**
     * Checks if a semantic memory already exists to prevent duplicate entropy.
     * If an exact semantic string exists, it updates the lastAccessed timestamp 
     * and increments the accessCount (reinforcing the memory), returning false.
     * If the memory is novel, it returns true.
     */
    fun shouldInsertMemory(memory: MemoryEntity): Boolean {
        val existing = chatDao.getMemoryByExactContent(memory.content)
        return if (existing != null) {
            chatDao.updateMemoryAccess(existing.id, System.currentTimeMillis())
            false
        } else {
            true
        }
    }
}
