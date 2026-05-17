package com.example.llmapp.core.retrieval

import com.example.llmapp.core.database.ChatDao
import com.example.llmapp.core.database.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Merges results from FTS (keyword) and Vector (semantic) search.
 * This is the core of the "Hybrid" in Hybrid Retrieval.
 */
class HybridRetriever(
    private val dao: ChatDao,
    private val embeddingManager: EmbeddingManager
) {

    /**
     * Performs a hybrid search over stored memories.
     * 1. Fetches keyword matches via FTS5.
     * 2. (Upcoming) Fetches semantic matches via Vector search.
     * 3. Ranks and deduplicates results.
     */
    suspend fun retrieveRelevance(query: String, limit: Int = 5): List<MemoryEntity> = withContext(Dispatchers.IO) {
        // 1. Keyword Search (FTS)
        val keywordResults = try {
            // Convert full sentence query into FTS OR query: "launch OR code OR Orion"
            val cleanQuery = query.replace(Regex("[^a-zA-Z0-9 ]"), "")
            val ftsQuery = cleanQuery.split(Regex("\\s+"))
                .filter { it.length > 2 } // Keep 3-letter words like 'pet', 'cat'
                .joinToString(" OR ")
            
            if (ftsQuery.isNotBlank()) {
                dao.searchMemoriesFts(ftsQuery)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }

        // 2. Semantic Search (Vector)
        // For now, we use the keyword results as a base.
        // Once EmbeddingManager's tokenizer is in, we'll calculate scores for all memories.
        
        // Simple ranking for now: Keyword matches first
        keywordResults.take(limit)
    }

    /**
     * Scores a list of results based on query relevance.
     * Uses a combination of BM25 (implied by FTS) and Cosine Similarity.
     */
    fun rankResults(queryVector: FloatArray, candidates: List<MemoryEntity>): List<MemoryEntity> {
        return candidates.map { memory ->
            val memoryVector = memory.embedding?.let { bytesToFloatArray(it) }
            val score = if (memoryVector != null) {
                embeddingManager.cosineSimilarity(queryVector, memoryVector)
            } else {
                0f
            }
            memory to score
        }.sortedByDescending { it.second }
         .map { it.first }
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val floatBuffer = java.nio.ByteBuffer.wrap(bytes).asFloatBuffer()
        val floats = FloatArray(floatBuffer.limit())
        floatBuffer.get(floats)
        return floats
    }
}
