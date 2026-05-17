package com.example.llmapp.core.search.rewriting

import com.example.llmapp.ChatMessage

/**
 * Clean coordinator for query rewriting. Normalizes and resolves follow-ups relative to history.
 */
object QueryRewriter {

    /**
     * Resolves the final optimized search query string by applying normalization and follow-up contextual resolution.
     */
    fun rewrite(currentText: String, history: List<ChatMessage>): String {
        return FollowUpResolver.rewriteQueryIfContextual(currentText, history)
    }
}
