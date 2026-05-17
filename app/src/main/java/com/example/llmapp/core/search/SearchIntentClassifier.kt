package com.example.llmapp.core.search

import com.example.llmapp.ChatMessage
import com.example.llmapp.core.search.classification.SearchReason
import com.example.llmapp.core.search.classification.SearchScorer
import com.example.llmapp.core.search.rules.MetaRules
import com.example.llmapp.core.search.rules.WeatherRules
import com.example.llmapp.core.search.rewriting.QueryNormalizer
import com.example.llmapp.core.search.rewriting.QueryRewriter
import com.example.llmapp.core.search.rewriting.FollowUpResolver
import java.util.Locale

/**
 * Zero-latency coordinator of system-driven search intent classification.
 * Delegates query preprocessing, follow-up state resolution, rule-based scoring, and execution policies.
 */
object SearchIntentClassifier {

    /**
     * Classifies the user query's search intent.
     */
    fun classify(rawText: String, history: List<ChatMessage> = emptyList()): SearchIntent {
        val text = rawText.trim()
        if (text.isBlank()) {
            return SearchIntent(
                needed = false,
                type = SearchType.NONE,
                query = null,
                confidence = 1.0f,
                reason = SearchReason.NONE
            )
        }

        val cleanLowerText = text.lowercase(Locale.getDefault()).trim()

        // 0. Bypasses Search Classification if query is a meta-conversational check/instruction directed at the AI
        if (MetaRules.BYPASS_PATTERNS.any { it.containsMatchIn(cleanLowerText) }) {
            return SearchIntent(
                needed = false,
                type = SearchType.NONE,
                query = null,
                confidence = 1.0f,
                reason = SearchReason.NONE
            )
        }

        // 1. User Forced Override: check if user explicitly demands an online search
        val isUserForced = MetaRules.USER_FORCED_KEYWORDS.any { keyword ->
            if (keyword.contains(" ")) {
                cleanLowerText.contains(keyword)
            } else {
                cleanLowerText.split("\\s+".toRegex()).contains(keyword)
            }
        }
        if (isUserForced) {
            val normalizedQuery = QueryNormalizer.cleanQuery(text)
            return SearchIntent(
                needed = true,
                type = SearchType.NEWS,
                query = normalizedQuery,
                confidence = 1.0f,
                reason = SearchReason.USER_FORCED
            )
        }

        // 2. Preprocess & Rewrite: Apply contextual follow-up query rewriting if history is present
        val isFollowUp = history.isNotEmpty() && FollowUpResolver.isContextualFollowUp(text.lowercase(Locale.getDefault()))
        val processedText = if (history.isNotEmpty()) {
            QueryRewriter.rewrite(text, history)
        } else {
            text
        }

        // 3. Contextual Inheritance: If follow-up and the previous turn had search, inherit intent
        if (isFollowUp) {
            val lastSearchQuery = FollowUpResolver.getLastSearchQuery(history)
            if (lastSearchQuery != null) {
                val processedLower = processedText.lowercase(Locale.getDefault())
                val isWeather = WeatherRules.rule.keywords.any { processedLower.contains(it) }
                val searchType = if (isWeather) SearchType.WEATHER else SearchType.NEWS
                return SearchIntent(
                    needed = true,
                    type = searchType,
                    query = QueryNormalizer.cleanQuery(processedText),
                    confidence = 0.95f,
                    reason = SearchReason.CONTEXTUAL_INHERITANCE
                )
            }
        }

        val URL_REGEX = Regex("(?i)\\b(?:https?://|www\\.)\\S+\\b|\\b\\S+\\.(?:com|org|net|edu|gov|io|co|me|dev|ai)\\b")

        // 4. WEBSITE_READ: Direct URL detection
        val urlMatch = URL_REGEX.find(processedText)
        if (urlMatch != null) {
            val url = urlMatch.value
            val cleanUrl = if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                "https://$url"
            } else {
                url
            }
            return SearchIntent(
                needed = true,
                type = SearchType.WEBSITE_READ,
                query = cleanUrl,
                confidence = 1.0f,
                reason = SearchReason.WEBSITE_READING
            )
        }

        // 5. WEBSITE_READ: Requesting article summarization
        val lowerText = processedText.lowercase(Locale.getDefault())
        if (lowerText.contains("summarize") || lowerText.contains("read") || lowerText.contains("crawl")) {
            val firstUrl = URL_REGEX.find(processedText)?.value
            if (firstUrl != null) {
                return SearchIntent(
                    needed = true,
                    type = SearchType.WEBSITE_READ,
                    query = firstUrl,
                    confidence = 0.95f,
                    reason = SearchReason.WEBSITE_READING
                )
            }
        }

        // 6. Perform modular dynamic scoring of rules
        val scoreResult = SearchScorer.score(processedText)
        
        // Prevents accidental triggers by thresholding confidence above 0.75f
        if (scoreResult.type != SearchType.NONE && scoreResult.confidence >= 0.75f) {
            return SearchIntent(
                needed = true,
                type = scoreResult.type,
                query = QueryNormalizer.cleanQuery(processedText),
                confidence = scoreResult.confidence,
                reason = scoreResult.reason
            )
        }

        // Default conversational path
        return SearchIntent(
            needed = false,
            type = SearchType.NONE,
            query = null,
            confidence = 1.0f,
            reason = SearchReason.NONE
        )
    }
}
