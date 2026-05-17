package com.example.llmapp.core.search.extraction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches raw HTML from a given URL.
 * Designed for mobile: strict timeouts, small payload expectations, safe fail-overs.
 */
object WebPageFetcher {

    // Short timeouts because we don't want to freeze the TTFT waiting for a slow site
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Exclude heavy/non-html extensions if they slip through the search results
    private val EXCLUDED_EXTENSIONS = listOf(".pdf", ".doc", ".docx", ".xls", ".ppt", ".zip", ".exe", ".mp4", ".png", ".jpg")

    /**
     * @return Raw HTML string or null if fetch failed/timed out.
     */
    suspend fun fetchHtml(url: String): String? = withContext(Dispatchers.IO) {
        val lowerUrl = url.lowercase()
        if (EXCLUDED_EXTENSIONS.any { lowerUrl.endsWith(it) }) {
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url(url)
                // Disguise as a standard mobile browser to avoid basic bot blocks
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.64 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                // Only read if it's text/html to avoid buffering huge binaries
                val contentType = response.header("Content-Type") ?: ""
                if (!contentType.contains("text/html", ignoreCase = true) && 
                    !contentType.contains("text/plain", ignoreCase = true)) {
                    return@withContext null
                }

                // Limit body to ~2MB max to prevent memory explosion on infinite scroll sites
                response.body?.source()?.let { source ->
                    source.request(2 * 1024 * 1024) 
                    return@withContext source.buffer.clone().readUtf8()
                }
            }
        } catch (e: Exception) {
            return@withContext null
        }
        return@withContext null
    }
}
