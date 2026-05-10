package com.example.llmapp.core.history

import android.content.Context
import com.example.llmapp.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File-based chat history storage.
 *
 * Each session is stored as a separate JSON file under:
 *   <filesDir>/sessions/<sessionId>.json
 *
 * A lightweight index file (sessions/index.json) tracks all session IDs.
 * This replaces the old SharedPreferences approach which had a hard ~1 MB
 * limit per string value, causing silent data loss on long conversations.
 *
 * Sessions from the old SharedPreferences store are automatically migrated
 * on first access.
 */
class ChatHistoryManager(private val context: Context) {

    private val sessionsDir: File
        get() = File(context.filesDir, "sessions").also { it.mkdirs() }

    private val indexFile: File
        get() = File(sessionsDir, "index.json")

    // ── Index helpers ────────────────────────────────────────────────────────

    private fun readIndexSync(): MutableSet<String> {
        if (!indexFile.exists()) return mutableSetOf()
        return try {
            val arr = JSONArray(indexFile.readText())
            mutableSetOf<String>().apply {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    private fun writeIndexSync(ids: Set<String>) {
        val arr = JSONArray().also { ids.forEach(it::put) }
        indexFile.writeText(arr.toString())
    }

    private fun sessionFile(sessionId: String) = File(sessionsDir, "$sessionId.json")

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun saveSession(sessionId: String, messages: List<ChatMessage>) =
        withContext(Dispatchers.IO) {
            // Migrate any old SharedPreferences data on first write
            migrateFromPrefsIfNeeded()

            val jsonArray = JSONArray()
            for (msg in messages) {
                val obj = JSONObject()
                obj.put("id", msg.id)
                obj.put("text", msg.text)
                obj.put("isUser", msg.isUser)
                obj.put("isError", msg.isError)
                obj.put("timestamp", msg.timestamp)
                // Bug #1 fix: persist rawContent so context is correct on restore
                obj.put("rawContent", msg.rawContent)
                jsonArray.put(obj)
            }
            sessionFile(sessionId).writeText(jsonArray.toString())

            val ids = readIndexSync()
            ids.add(sessionId)
            writeIndexSync(ids)
        }

    suspend fun loadSession(sessionId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            migrateFromPrefsIfNeeded()

            val file = sessionFile(sessionId)
            if (!file.exists()) return@withContext emptyList()

            try {
                val arr = JSONArray(file.readText())
                val messages = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    messages.add(
                        ChatMessage(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            text = obj.getString("text"),
                            isUser = obj.getBoolean("isUser"),
                            isError = obj.optBoolean("isError", false),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            rawContent = obj.optString("rawContent", "")
                        )
                    )
                }
                messages
            } catch (_: Exception) {
                emptyList()
            }
        }

    suspend fun getSessionIds(): Set<String> = withContext(Dispatchers.IO) {
        migrateFromPrefsIfNeeded()
        readIndexSync()
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        sessionFile(sessionId).delete()
        val ids = readIndexSync()
        ids.remove(sessionId)
        writeIndexSync(ids)
    }

    // ── One-time migration from old SharedPreferences store ──────────────────

    private var migrationDone = false

    private fun migrateFromPrefsIfNeeded() {
        if (migrationDone) return
        migrationDone = true

        val prefs = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)
        val oldIds = prefs.getStringSet("all_sessions", emptySet()) ?: return

        if (oldIds.isEmpty()) return

        val newIds = readIndexSync()
        for (id in oldIds) {
            if (id in newIds) continue // already migrated
            val json = prefs.getString("session_$id", null) ?: continue
            try {
                val oldArr = JSONArray(json)
                val newArr = JSONArray()
                for (i in 0 until oldArr.length()) {
                    val old = oldArr.getJSONObject(i)
                    val obj = JSONObject()
                    obj.put("id", java.util.UUID.randomUUID().toString())
                    obj.put("text", old.getString("text"))
                    obj.put("isUser", old.getBoolean("isUser"))
                    obj.put("isError", old.optBoolean("isError", false))
                    obj.put("timestamp", System.currentTimeMillis())
                    obj.put("rawContent", "") // rawContent was never saved in old store
                    newArr.put(obj)
                }
                sessionFile(id).writeText(newArr.toString())
                newIds.add(id)
            } catch (_: Exception) { /* skip corrupt entries */ }
        }
        writeIndexSync(newIds)

        // Clean up old SharedPreferences entries
        prefs.edit().clear().apply()
    }
}
