package com.example.llmapp.core.search.rewriting

import com.example.llmapp.ChatMessage
import java.util.Locale

/**
 * Resolves conversational context across conversational history to rewrite short or pronoun-heavy follow-up queries.
 */
object FollowUpResolver {
    
    fun isContextualFollowUp(lowerText: String): Boolean {
        val words = lowerText.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return false

        // 1. Very short queries (1-2 words) are highly likely to be follow-ups if they contain relative terms
        if (words.size <= 2) {
            val contextualTerms = setOf(
                "currently", "now", "today", "tomorrow", "yesterday", "why", "how", "who", "where",
                "he", "she", "it", "they", "him", "her", "his", "its", "them", "their", "there", "then", "current"
            )
            if (words.any { contextualTerms.contains(it.replace(Regex("[?\\!.,]"), "")) }) {
                return true
            }
        }

        // 2. Starts with conversational transition starters
        val transitions = listOf("and", "what about", "how about", "or", "but")
        if (transitions.any { lowerText.startsWith(it) }) {
            return true
        }

        // 3. Contains pronouns indicating reference to previous subject
        val pronouns = setOf("he", "she", "it", "they", "him", "her", "his", "its", "them", "their", "there", "then", "currently", "current")
        if (words.any { pronouns.contains(it.replace(Regex("[?\\!.,]"), "")) }) {
            return true
        }

        return false
    }

    fun getLastSearchQuery(history: List<ChatMessage>): String? {
        val URL_REGEX = Regex("""(?i)\bhttps?://[^\s/$.?#].[^\s]*\b""")
        for (i in history.indices.reversed()) {
            val msg = history[i]
            if (!msg.isUser) {
                val searchAction = msg.actions.lastOrNull { it.toolName == "Web Search" }
                if (searchAction != null && searchAction.query.isNotBlank()) {
                    val q = searchAction.query.trim()
                    // Filter out URL deep crawls to avoid polluting follow-up contexts
                    if (!q.startsWith("http://", ignoreCase = true) && 
                        !q.startsWith("https://", ignoreCase = true) && 
                        !URL_REGEX.matches(q)) {
                        return q
                    }
                }
            }
        }
        return null
    }

    fun rewriteQueryIfContextual(currentText: String, history: List<ChatMessage>): String {
        val userMessages = history.filter { it.isUser }
        val lastUserMessage = if (userMessages.isNotEmpty() && userMessages.last().text.trim() == currentText.trim()) {
            if (userMessages.size >= 2) userMessages[userMessages.size - 2] else null
        } else {
            userMessages.lastOrNull()
        } ?: return currentText

        val prevText = lastUserMessage.text.trim()
        val currText = currentText.trim()
        val currLower = currText.lowercase(Locale.getDefault())

        // 1. Check if the current query is a contextual follow-up
        if (!isContextualFollowUp(currLower)) return currentText

        // 2. Resolve the base context (prefer the last successful web search query over raw user text)
        val baseContext = getLastSearchQuery(history) ?: QueryNormalizer.cleanQuery(prevText)
        val baseLower = baseContext.lowercase(Locale.getDefault())

        // 3. Perform query rewrite based on patterns
        val cleanedCurr = QueryNormalizer.cleanQuery(currText)

        // Pattern A: Location/Entity replacement
        // E.g., baseContext = "the prime minister of India currently", current = "and USA"
        val locationPrepositions = listOf(" in ", " at ", " for ", " of ")
        for (prep in locationPrepositions) {
            val prepIndex = baseLower.indexOf(prep)
            if (prepIndex != -1) {
                val prefix = baseContext.substring(0, prepIndex + prep.length)
                val strippedCurr = QueryNormalizer.stripTransitions(currText)
                // If the follow-up is very short (e.g. 1-2 words representing the new location/entity)
                if (strippedCurr.split("\\s+".toRegex()).size <= 2) {
                    val suffix = baseContext.substring(prepIndex + prep.length)
                    val suffixWords = suffix.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    if (suffixWords.isNotEmpty()) {
                        val wordToReplace = suffixWords[0]
                        val newSuffix = suffix.replaceFirst(wordToReplace, strippedCurr)
                        return prefix + newSuffix
                    }
                    return prefix + strippedCurr
                }
            }
        }

        // Pattern B: Temporal shift
        // E.g., current = "tomorrow", "currently", "now", "today", "yesterday", "next week"
        val temporalShifts = setOf("tomorrow", "currently", "now", "today", "yesterday", "next week")
        if (temporalShifts.contains(cleanedCurr.lowercase(Locale.getDefault()))) {
            return "$baseContext $cleanedCurr"
        }

        // Pattern C: General conversational reference or relative pronoun
        // E.g. "who is he?" / "what is his age?" / "what is the price currently?"
        val hasPronoun = currLower.split("\\s+".toRegex()).any {
            setOf("he", "she", "him", "her", "his", "it", "its", "they", "them", "their", "there", "then", "currently", "current").contains(it.replace(Regex("[?\\!.,]"), ""))
        }
        if (hasPronoun) {
            return "$baseContext $cleanedCurr"
        }

        // Default Pattern: merge cleaned base context with stripped current transitions
        val strippedCurr = QueryNormalizer.stripTransitions(currText)
        return "$baseContext $strippedCurr"
    }
}
