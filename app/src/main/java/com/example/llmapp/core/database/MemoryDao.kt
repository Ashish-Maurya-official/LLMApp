package com.example.llmapp.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Dedicated DAO for all memory operations across Profile, Semantic, and Episode stores.
 * Separated from ChatDao for clean memory-system boundaries.
 */
@Dao
interface MemoryDao {

    // ── Profile Store ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProfile(memory: ProfileMemoryEntity): Long

    /** Get all currently-active profile memories (validTo is null). */
    @Query("SELECT * FROM profile_memories WHERE validTo IS NULL ORDER BY importanceScore DESC")
    fun getActiveProfiles(): List<ProfileMemoryEntity>

    /** Search profiles by key or value substring match. */
    @Query("SELECT * FROM profile_memories WHERE validTo IS NULL AND (key LIKE '%' || :term || '%' OR value LIKE '%' || :term || '%') ORDER BY importanceScore DESC")
    fun searchProfiles(term: String): List<ProfileMemoryEntity>

    /** Expire (soft-delete) a profile entry by key — sets validTo to now. */
    @Query("UPDATE profile_memories SET validTo = :ts WHERE key = :key AND validTo IS NULL")
    fun expireProfile(key: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE profile_memories SET accessCount = accessCount + 1, lastAccessed = :ts WHERE id IN (:ids)")
    fun batchUpdateProfileAccess(ids: List<Long>, ts: Long = System.currentTimeMillis())

    // ── Semantic Store ───────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSemantic(memory: SemanticMemoryEntity): Long

    /** FTS keyword search on semantic memories, ordered by importance and usage. */
    @Query("""
        SELECT s.* FROM semantic_memories s 
        JOIN semantic_memories_fts ON s.id = semantic_memories_fts.rowid 
        WHERE semantic_memories_fts MATCH :query 
        ORDER BY s.importanceScore DESC, s.accessCount DESC
        LIMIT :limit
    """)
    fun searchSemanticFts(query: String, limit: Int): List<SemanticMemoryEntity>

    /** Get recent semantic memories when no keywords are available. */
    @Query("SELECT * FROM semantic_memories ORDER BY importanceScore DESC, lastAccessed DESC LIMIT :limit")
    fun getRecentSemantic(limit: Int): List<SemanticMemoryEntity>

    /** Check for duplicate content before inserting. */
    @Query("SELECT * FROM semantic_memories WHERE content = :content LIMIT 1")
    fun getSemanticByExactContent(content: String): SemanticMemoryEntity?

    @Query("UPDATE semantic_memories SET accessCount = accessCount + 1, lastAccessed = :ts WHERE id IN (:ids)")
    fun batchUpdateSemanticAccess(ids: List<Long>, ts: Long = System.currentTimeMillis())

    // ── Episode Store ────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEpisode(episode: EpisodeEntity): Long

    /** Search episodes by topic, outcome, or key facts substring. */
    @Query("""
        SELECT * FROM episodes 
        WHERE topic LIKE '%' || :term || '%' 
           OR outcome LIKE '%' || :term || '%' 
           OR keyFacts LIKE '%' || :term || '%' 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    fun searchEpisodes(term: String, limit: Int): List<EpisodeEntity>

    /** Get most recent episodes. */
    @Query("SELECT * FROM episodes ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEpisodes(limit: Int): List<EpisodeEntity>

    // ── Chapter / Book Store ─────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChapter(chapter: ChapterEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertBook(book: BookEntity): Long

    @Query("SELECT * FROM chapters ORDER BY lastUpdated DESC LIMIT :limit")
    fun getRecentChapters(limit: Int): List<ChapterEntity>

    @Query("SELECT * FROM episodes WHERE chapterId = :chapterId ORDER BY timestamp ASC")
    fun getEpisodesForChapter(chapterId: Long): List<EpisodeEntity>

    @Query("UPDATE chapters SET episodeCount = episodeCount + 1, lastUpdated = :ts WHERE id = :chapterId")
    fun incrementChapterEpisodeCount(chapterId: Long, ts: Long = System.currentTimeMillis())
}
