package com.example.llmapp.core.search.settings

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for user-supplied API credentials using [EncryptedSharedPreferences].
 *
 * Security rules enforced:
 *  - Keys are never logged.
 *  - Keys are never exposed in crash reports.
 *  - All writes are applied asynchronously.
 */
class SecureSearchStorage(context: Context) {

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "search_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("SecureSearchStorage", "Failed to create EncryptedSharedPreferences: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Stores the Google Custom Search API Key securely. */
    fun saveGoogleApiKey(key: String) {
        prefs?.edit()?.putString(KEY_GOOGLE_API, key)?.apply()
    }

    /** Stores the Google Custom Search Engine ID (CX) securely. */
    fun saveGoogleCxId(cxId: String) {
        prefs?.edit()?.putString(KEY_GOOGLE_CX, cxId)?.apply()
    }

    /**
     * Returns the stored Google credentials as a Pair.
     * Returns Pair("", "") if not configured.
     */
    fun getGoogleCredentials(): Pair<String, String> {
        val apiKey = prefs?.getString(KEY_GOOGLE_API, "") ?: ""
        val cxId = prefs?.getString(KEY_GOOGLE_CX, "") ?: ""
        return Pair(apiKey, cxId)
    }

    fun clearGoogleCredentials() {
        prefs?.edit()?.remove(KEY_GOOGLE_API)?.remove(KEY_GOOGLE_CX)?.apply()
    }

    fun isGoogleConfigured(): Boolean {
        val (key, cx) = getGoogleCredentials()
        return key.isNotBlank() && cx.isNotBlank()
    }

    private companion object {
        const val KEY_GOOGLE_API = "google_api_key"
        const val KEY_GOOGLE_CX = "google_cx_id"
    }
}
