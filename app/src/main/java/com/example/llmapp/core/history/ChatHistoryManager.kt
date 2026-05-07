package com.example.llmapp.core.history

import android.content.Context
import android.content.SharedPreferences
import com.example.llmapp.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

class ChatHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("chat_history", Context.MODE_PRIVATE)

    fun saveSession(sessionId: String, messages: List<ChatMessage>) {
        val jsonArray = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("text", msg.text)
            obj.put("isUser", msg.isUser)
            obj.put("isError", msg.isError)
            jsonArray.put(obj)
        }
        prefs.edit().putString("session_$sessionId", jsonArray.toString()).apply()
        
        // Update list of sessions
        val sessions = getSessionIds().toMutableSet()
        sessions.add(sessionId)
        prefs.edit().putStringSet("all_sessions", sessions).apply()
    }

    fun loadSession(sessionId: String): List<ChatMessage> {
        val jsonString = prefs.getString("session_$sessionId", null) ?: return emptyList()
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
        return messages
    }

    fun getSessionIds(): Set<String> {
        return prefs.getStringSet("all_sessions", emptySet()) ?: emptySet()
    }

    fun deleteSession(sessionId: String) {
        prefs.edit().remove("session_$sessionId").apply()
        val sessions = getSessionIds().toMutableSet()
        sessions.remove(sessionId)
        prefs.edit().putStringSet("all_sessions", sessions).apply()
    }
}
