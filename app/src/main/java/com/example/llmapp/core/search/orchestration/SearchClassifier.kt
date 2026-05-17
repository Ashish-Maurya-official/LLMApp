package com.example.llmapp.core.search.orchestration

enum class SearchIntent {
    SNIPPET, // Fast snippet search only
    DEEP     // Needs full webpage extraction
}

/**
 * Result of the search classifier's analysis of a query.
 */
data class SearchDecision(
    val needed: Boolean,
    val confidence: Float,
    val rewrittenQuery: String,
    val intent: SearchIntent = SearchIntent.SNIPPET
)

/**
 * Smart heuristic classifier that determines if a query needs a live web search.
 *
 * Runs entirely in-memory with no network calls — zero latency.
 * Acts as the primary gate before any API is called.
 */
object SearchClassifier {

    private val DENY_LIST = setOf(
        "hello", "hi", "hey", "thanks", "thank you", "ok", "okay", "bye", "goodbye",
        "how are you", "what are you", "who are you", "tell me about yourself",
        "what can you do", "help me", "yes", "no", "sure", "great", "awesome", "cool"
    )

    // Keywords that strongly imply real-time need
    private val REALTIME_KEYWORDS = setOf(
        "today", "now", "current", "latest", "live", "recent", "breaking",
        "right now", "this week", "this month", "2024", "2025", "2026",
        "weather", "temperature", "forecast", "rain", "sunny",
        "news", "headlines", "update", "announcement",
        "price", "stock", "bitcoin", "crypto", "usd", "inr", "rate",
        "score", "match", "result", "ipl", "cricket", "football", "soccer",
        "who won", "who is winning", "standings", "leaderboard",
        "release", "launch", "available", "new version", "update"
    )

    // General knowledge — should NOT trigger search
    private val STATIC_KNOWLEDGE_KEYWORDS = setOf(
        "what is", "explain", "define", "how does", "why does",
        "history of", "who invented", "capital of", "meaning of",
        "difference between", "compare", "translate", "code", "write",
        "calculate", "solve", "formula", "algorithm"
    )

    // Keywords that imply the user wants us to read a specific article or deep-dive
    private val DEEP_BROWSE_KEYWORDS = setOf(
        "summarize this article", "read this", "analyze this webpage", "extract from",
        "http://", "https://"
    )

    /**
     * @param query The raw user query to classify.
     * @return [SearchDecision] with `needed`, a `confidence` score, and an optimized `rewrittenQuery`.
     */
    fun classify(query: String): SearchDecision {
        val lower = query.lowercase().trim()

        // Hard deny — trivial conversational messages
        if (DENY_LIST.any { lower == it || lower.startsWith(it) }) {
            return SearchDecision(needed = false, confidence = 0f, rewrittenQuery = query)
        }

        // Hard deny — pure static knowledge questions
        val hasStaticSignal = STATIC_KNOWLEDGE_KEYWORDS.any { lower.contains(it) }
        val hasRealtimeSignal = REALTIME_KEYWORDS.any { lower.contains(it) }

        // Static knowledge + no real-time signal = skip search
        if (hasStaticSignal && !hasRealtimeSignal) {
            return SearchDecision(needed = false, confidence = 0.1f, rewrittenQuery = query)
        }

        // Calculate confidence from real-time keyword hits
        val hitCount = REALTIME_KEYWORDS.count { lower.contains(it) }
        val confidence = when {
            hitCount >= 3 -> 0.95f
            hitCount == 2 -> 0.85f
            hitCount == 1 -> 0.70f
            else -> 0.3f
        }

        val needed = confidence >= 0.60f || DEEP_BROWSE_KEYWORDS.any { lower.contains(it) }
        val rewrittenQuery = if (needed) rewriteQuery(query, lower) else query

        val intent = if (DEEP_BROWSE_KEYWORDS.any { lower.contains(it) }) SearchIntent.DEEP else SearchIntent.SNIPPET

        return SearchDecision(
            needed = needed,
            confidence = if (intent == SearchIntent.DEEP) 1.0f else confidence,
            rewrittenQuery = rewrittenQuery,
            intent = intent
        )
    }

    /**
     * Lightweight query rewriter that improves search quality.
     * Example: "what happened to gemini" → "latest Google Gemini AI updates 2025"
     */
    private fun rewriteQuery(original: String, lower: String): String {
        return when {
            lower.contains("weather") && !lower.contains("today") ->
                "$original today"
            lower.contains("price") || lower.contains("stock") ->
                "$original live rate"
            lower.contains("score") || lower.contains("match") ->
                "$original live result"
            lower.contains("news") && !lower.contains("latest") ->
                "latest $original"
            else -> original
        }
    }
}
