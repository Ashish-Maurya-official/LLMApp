package com.example.llmapp.core.history

import android.content.Context
import android.content.SharedPreferences
import com.example.llmapp.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)

    suspend fun saveSession(sessionId: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        val jsonArray = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("text", msg.text)
            obj.put("isUser", msg.isUser)
            obj.put("isError", msg.isError)
            jsonArray.put(obj)
        }
        prefs.edit().putString("session_$sessionId", jsonArray.toString()).apply()
        
        val sessions = getSessionIdsSync().toMutableSet()
        sessions.add(sessionId)
        prefs.edit().putStringSet("all_sessions", sessions).apply()
    }

    suspend fun loadSession(sessionId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        val jsonString = prefs.getString("session_$sessionId", null) ?: return@withContext emptyList()
        val jsonArray = JSONArray(jsonString)
        val messages = mutableListOf<ChatMessage>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            messages.add(
                ChatMessage(
                    text = obj.getString("text"),
                    isUser = obj.getBoolean("isUser"),
                    isError = obj.optBoolean("isError", false)
                )
            )
        }
        return@withContext messages
    }

    suspend fun getSessionIds(): Set<String> = withContext(Dispatchers.IO) {
        getSessionIdsSync()
    }
    
    private fun getSessionIdsSync(): Set<String> {
        return prefs.getStringSet("all_sessions", emptySet()) ?: emptySet()
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove("session_$sessionId").apply()
        val sessions = getSessionIdsSync().toMutableSet()
        sessions.remove(sessionId)
        prefs.edit().putStringSet("all_sessions", sessions).apply()
    }
}
