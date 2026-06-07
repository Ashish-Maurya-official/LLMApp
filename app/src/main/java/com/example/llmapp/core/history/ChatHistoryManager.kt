package com.example.llmapp.core.history

import android.content.Context
import com.example.llmapp.ChatMessage
import com.example.llmapp.core.database.ChatDatabase
import com.example.llmapp.core.database.MessageEntity
import com.example.llmapp.core.database.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Database-backed chat history storage.
 *
 * Migrates old JSON sessions to Room database automatically.
 */
class ChatHistoryManager(val context: Context) {

    val database = ChatDatabase.getDatabase(context)
    private val dao = database.chatDao()

    fun chatDao() = dao

    private val sessionsDir: File
        get() = File(context.filesDir, "sessions")

    private val indexFile: File
        get() = File(sessionsDir, "index.json")

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun saveSession(sessionId: String, messages: List<ChatMessage>) =
        withContext(Dispatchers.IO) {
            migrateFromJsonIfNeeded()

            // Find an appropriate title from the first user message, or default
            val title = messages.firstOrNull { it.isUser }?.text?.take(30) ?: "New Chat"
            
            // Upsert session
            dao.insertSession(
                SessionEntity(
                    id = sessionId,
                    title = title,
                    timestamp = messages.lastOrNull()?.timestamp ?: System.currentTimeMillis()
                )
            )

            // Upsert messages
            val messageEntities = messages.map { msg ->
                MessageEntity(
                    id = msg.id,
                    sessionId = sessionId,
                    isUser = msg.isUser,
                    text = msg.text,
                    rawContent = msg.rawContent,
                    timestamp = msg.timestamp,
                    thoughtsJson = thoughtsToJson(msg.thoughts),
                    actionsJson = "[]" // Deprecated, kept for DB compat
                )
            }
            dao.insertMessages(messageEntities)
        }

    /**
     * Saves a single [ChatMessage] to the DB and ensures the session row exists.
     * Much cheaper than [saveSession] — use this for individual user/AI messages
     * as they arrive, to avoid re-inserting the entire conversation each turn.
     */
    suspend fun saveMessage(sessionId: String, message: ChatMessage) =
        withContext(Dispatchers.IO) {
            migrateFromJsonIfNeeded()
            // Make sure the session row exists first
            val existing = dao.getSession(sessionId)
            if (existing == null) {
                dao.insertSession(
                    SessionEntity(
                        id = sessionId,
                        title = if (message.isUser) message.text.take(30) else "New Chat",
                        timestamp = message.timestamp
                    )
                )
            }
            dao.insertMessage(
                MessageEntity(
                    id = message.id,
                    sessionId = sessionId,
                    isUser = message.isUser,
                    text = message.text,
                    rawContent = message.rawContent,
                    timestamp = message.timestamp,
                    thoughtsJson = thoughtsToJson(message.thoughts),
                    actionsJson = "[]" // Deprecated, kept for DB compat
                )
            )
        }

    /**
     * Updates only the session metadata (title, timestamp) without touching message rows.
     * Call after the AI response is done to refresh the session list title/timestamp.
     */
    suspend fun upsertSession(sessionId: String, messages: List<ChatMessage>) =
        withContext(Dispatchers.IO) {
            val title = messages.firstOrNull { it.isUser }?.text?.take(30) ?: "New Chat"
            dao.insertSession(
                SessionEntity(
                    id = sessionId,
                    title = title,
                    timestamp = messages.lastOrNull()?.timestamp ?: System.currentTimeMillis()
                )
            )
        }


    suspend fun loadSession(sessionId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            migrateFromJsonIfNeeded()

            val entities = dao.getMessagesForSession(sessionId)
            entities.map { entity ->
                ChatMessage(
                    id = entity.id,
                    text = entity.text,
                    isUser = entity.isUser,
                    timestamp = entity.timestamp,
                    rawContent = entity.rawContent,
                    thoughts = jsonToThoughts(entity.thoughtsJson)
                )
            }
        }

    fun getMessagesPagingSource(sessionId: String): androidx.paging.PagingSource<Int, com.example.llmapp.core.database.MessageEntity> {
        return dao.getMessagesPagingSource(sessionId)
    }

    /** Serialize ThoughtItem list to JSON */
    private fun thoughtsToJson(thoughts: List<com.example.llmapp.core.runtime.ThoughtItem>): String {
        val arr = JSONArray()
        thoughts.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("source", item.source.name)
            obj.put("title", item.title)
            obj.put("updates", JSONArray(item.updates))
            obj.put("state", item.state.name)
            obj.put("timestamp", item.timestamp)
            arr.put(obj)
        }
        return arr.toString()
    }

    /** Deserialize ThoughtItem list from JSON, with backward compat for old List<String> format */
    private fun jsonToThoughts(json: String): List<com.example.llmapp.core.runtime.ThoughtItem> {
        return try {
            val arr = JSONArray(json)
            if (arr.length() == 0) return emptyList()
            List(arr.length()) { i ->
                val item = arr.get(i)
                if (item is String) {
                    // Legacy: plain string → migrate to ThoughtItem
                    com.example.llmapp.core.runtime.ThoughtItem(
                        id = java.util.UUID.randomUUID().toString(),
                        source = com.example.llmapp.core.runtime.ThoughtSource.ORCHESTRATOR,
                        title = item,
                        updates = emptyList(),
                        state = com.example.llmapp.core.runtime.ThoughtState.COMPLETED,
                        timestamp = 0L
                    )
                } else {
                    val obj = item as JSONObject
                    val updatesArr = obj.optJSONArray("updates")
                    val updates = if (updatesArr != null) {
                        List(updatesArr.length()) { j -> updatesArr.getString(j) }
                    } else emptyList()
                    com.example.llmapp.core.runtime.ThoughtItem(
                        id = obj.getString("id"),
                        source = com.example.llmapp.core.runtime.ThoughtSource.valueOf(
                            obj.optString("source", "ORCHESTRATOR")
                        ),
                        title = obj.getString("title"),
                        updates = updates,
                        state = com.example.llmapp.core.runtime.ThoughtState.valueOf(
                            obj.optString("state", "COMPLETED")
                        ),
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getSessionIds(): Set<String> = withContext(Dispatchers.IO) {
        migrateFromJsonIfNeeded()
        dao.getAllSessions().map { it.id }.toSet()
    }

    suspend fun getSessions(): List<com.example.llmapp.core.database.SessionEntity> = withContext(Dispatchers.IO) {
        migrateFromJsonIfNeeded()
        dao.getAllSessions()
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        dao.deleteSession(sessionId)
    }

    // ── One-time migration from old JSON file store ─────────────────────────

    private var migrationDone = false

    private suspend fun migrateFromJsonIfNeeded() {
        if (migrationDone) return
        
        withContext(Dispatchers.IO) {
            val sessionsFromDb = dao.getAllSessions().map { it.id }.toSet()
            if (!indexFile.exists()) {
                migrationDone = true
                return@withContext
            }

            try {
                val arr = JSONArray(indexFile.readText())
                val oldIds = mutableSetOf<String>()
                for (i in 0 until arr.length()) oldIds.add(arr.getString(i))

                for (id in oldIds) {
                    if (id in sessionsFromDb) continue // already migrated

                    val file = File(sessionsDir, "$id.json")
                    if (!file.exists()) continue

                    try {
                        val msgArr = JSONArray(file.readText())
                        val messagesToInsert = mutableListOf<MessageEntity>()
                        var firstUserText = "New Chat"
                        var lastTimestamp = System.currentTimeMillis()

                        for (i in 0 until msgArr.length()) {
                            val obj = msgArr.getJSONObject(i)
                            val text = obj.getString("text")
                            val isUser = obj.getBoolean("isUser")
                            val msgId = obj.optString("id", UUID.randomUUID().toString())
                            val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                            val rawContent = obj.optString("rawContent", "")

                            if (isUser && firstUserText == "New Chat") {
                                firstUserText = text.take(30)
                            }
                            lastTimestamp = timestamp

                            messagesToInsert.add(
                                MessageEntity(
                                    id = msgId,
                                    sessionId = id,
                                    isUser = isUser,
                                    text = text,
                                    rawContent = rawContent,
                                    timestamp = timestamp
                                )
                            )
                        }

                        // Insert session
                        dao.insertSession(
                            SessionEntity(
                                id = id,
                                title = firstUserText,
                                timestamp = lastTimestamp
                            )
                        )
                        // Insert messages
                        dao.insertMessages(messagesToInsert)
                        
                        // Clean up file
                        file.delete()
                    } catch (_: Exception) { /* skip corrupt */ }
                }
                
                // Remove index file after full migration
                indexFile.delete()
            } catch (_: Exception) {}
            
            migrationDone = true
        }
    }
}
