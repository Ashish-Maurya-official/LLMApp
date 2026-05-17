package com.example.llmapp.core.search.providers

import android.util.Log
import com.example.llmapp.core.search.models.SearchRequest
import com.example.llmapp.core.search.models.SearchResponse
import com.example.llmapp.core.search.models.SearchResult
import com.example.llmapp.core.search.settings.SecureSearchStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Custom Search JSON API provider.
 *
 * Requires user-supplied credentials stored in [SecureSearchStorage]:
 * - API Key  (Google Cloud Console)
 * - CX ID    (Programmable Search Engine ID)
 *
 * Endpoint: https://www.googleapis.com/customsearch/v1
 * Free tier: 100 queries/day.
 */
class GoogleSearchProvider(
    private val secureStorage: SecureSearchStorage
) : SearchProvider {

    override val providerName = "Google Custom Search"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun isConfigured(): Boolean {
        val (apiKey, cxId) = secureStorage.getGoogleCredentials()
        return apiKey.isNotBlank() && cxId.isNotBlank()
    }

    override suspend fun validateConfiguration(): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext "✕ Not configured — enter API Key and CX ID"
        try {
            val response = executeApiCall("connection test", 1)
            when {
                response.isSuccess -> "✓ Connected Successfully (${response.results.size} result fetched)"
                response.error?.contains("400") == true -> "✕ Invalid CX ID — check your Search Engine ID"
                response.error?.contains("403") == true -> "✕ Invalid API Key or quota exceeded"
                response.error?.contains("429") == true -> "✕ Quota Exceeded — try again tomorrow"
                else -> "✕ Error: ${response.error}"
            }
        } catch (e: Exception) {
            "✕ Network Error: ${e.message}"
        }
    }

    override suspend fun search(request: SearchRequest): SearchResponse = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext SearchResponse(error = "Google Search not configured")
        }
        executeApiCall(request.query, request.maxResults.coerceIn(1, 5))
    }

    private suspend fun executeApiCall(query: String, num: Int): SearchResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val (apiKey, cxId) = secureStorage.getGoogleCredentials()

        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.googleapis.com/customsearch/v1" +
                "?key=$apiKey" +
                "&cx=$cxId" +
                "&q=$encodedQuery" +
                "&num=$num" +
                "&safe=active"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e("GoogleSearchProvider", "HTTP ${response.code}: query execution failed")
                return@withContext SearchResponse(
                    error = "HTTP ${response.code}",
                    providerUsed = providerName,
                    durationMs = System.currentTimeMillis() - start
                )
            }

            val results = parseResults(body)
            SearchResponse(
                results = results,
                providerUsed = providerName,
                durationMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.e("GoogleSearchProvider", "Search failed: ${e.javaClass.simpleName}")
            SearchResponse(
                error = e.javaClass.simpleName,
                providerUsed = providerName,
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun parseResults(json: String): List<SearchResult> {
        return try {
            val root = JSONObject(json)
            // Google returns HTTP 200 with no "items" when no results found — not an error.
            val items = root.optJSONArray("items") ?: return emptyList()
            val results = mutableListOf<SearchResult>()
            for (i in 0 until items.length()) {
                try {
                    val item = items.getJSONObject(i)
                    val snippet = item.optString("snippet", "").replace("\n", " ").trim()
                    // displayLink is always a String — never a JSONObject
                    val source = item.optString("displayLink", "")
                    results.add(
                        SearchResult(
                            title = item.optString("title", "").ifBlank { "Untitled" },
                            snippet = snippet.take(300),
                            url = item.optString("link", ""),
                            source = source
                        )
                    )
                } catch (itemEx: Exception) {
                    Log.w("GoogleSearchProvider", "Skipping malformed result item[$i]: ${itemEx.message}")
                }
            }
            results
        } catch (e: Exception) {
            Log.e("GoogleSearchProvider", "JSON parse error: ${e.message}")
            emptyList()
        }
    }
}
