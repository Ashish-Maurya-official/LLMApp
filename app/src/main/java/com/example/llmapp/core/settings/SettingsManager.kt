package com.example.llmapp.core.settings

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("llm_settings", Context.MODE_PRIVATE)

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 1024)
        set(value) = prefs.edit().putInt("max_tokens", value).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.8f)
        set(value) = prefs.edit().putFloat("temperature", value).apply()

    var topK: Int
        get() = prefs.getInt("top_k", 40)
        set(value) = prefs.edit().putInt("top_k", value).apply()
        
    var systemPrompt: String
        get() = prefs.getString("system_prompt", "You are a helpful offline AI. If the user asks a question requiring the internet, output EXACTLY AND ONLY: SEARCH_ONLINE: [your query here]") ?: ""
        set(value) = prefs.edit().putString("system_prompt", value).apply()
        
    var themePreference: String
        get() = prefs.getString("theme_preference", "System") ?: "System"
        set(value) = prefs.edit().putString("theme_preference", value).apply()

    var ttsVoiceName: String
        get() = prefs.getString("tts_voice_name", "") ?: ""
        set(value) = prefs.edit().putString("tts_voice_name", value).apply()

    var ttsSpeechRate: Float
        get() = prefs.getFloat("tts_speech_rate", 0.95f)
        set(value) = prefs.edit().putFloat("tts_speech_rate", value).apply()

    var contextLimit: Int
        get() = prefs.getInt("context_limit", 10)
        set(value) = prefs.edit().putInt("context_limit", value).apply()

    var language: String
        get() = prefs.getString("language", "English") ?: "English"
        set(value) = prefs.edit().putString("language", value).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value).apply()

    var userDob: String
        get() = prefs.getString("user_dob", "") ?: ""
        set(value) = prefs.edit().putString("user_dob", value).apply()

    var userLocation: String
        get() = prefs.getString("user_location", "") ?: ""
        set(value) = prefs.edit().putString("user_location", value).apply()

    var userBio: String
        get() = prefs.getString("user_bio", "") ?: ""
        set(value) = prefs.edit().putString("user_bio", value).apply()
}
