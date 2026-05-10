package com.example.llmapp.core.retrieval

import com.example.llmapp.ChatMessage

/**
 * Builds the full prompt context for each LLM call.
 *
 * Layers injected (in order):
 *  1. System instructions (capability declaration + tool instructions)
 *  2. User profile (name, location, bio) — if available
 *  3. Relevant memories retrieved from local DB (hybrid FTS + vector)
 *  4. Recent conversation history (sliding window, newest last)
 *  5. Current user query
 *
 * Tool protocol:
 *  - If the LLM needs a real-time web search it outputs the sentinel:
 *      [SEARCH_NEEDED]
 *    The ViewModel intercepts this token, performs the search, and calls
 *    generateResponseAsync again with the search results injected.
 */
class ContextManager(
    private val hybridRetriever: HybridRetriever,
    private val maxHistoryMessages: Int = 12
) {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * @param query            The current user message.
     * @param recentHistory    The in-memory message list (allMessages).
     * @param systemPrompt     Base system prompt from SettingsManager.
     * @param userProfile      Optional map of profile fields (name, dob, location, bio).
     */
    suspend fun buildContext(
        query: String,
        recentHistory: List<ChatMessage>,
        systemPrompt: String,
        userProfile: Map<String, String> = emptyMap()
    ): String {

        // 1. Retrieve semantically relevant memories from local DB
        val memories = hybridRetriever.retrieveRelevance(query)

        val sb = StringBuilder()

        // ── System turn ───────────────────────────────────────────────────────
        sb.append("<start_of_turn>user\n")
        sb.append(systemPrompt.trim())
        sb.append("\n\n")

        // Tool-use instructions — always present so the LLM knows it can search
        sb.append(
            """
            ## Available Tools
            You have access to the following tools. Use them only when necessary.

            **[SEARCH_NEEDED]** — Output EXACTLY this token (and nothing else on that line)
            when the user's question requires real-time internet information (news, prices,
            weather, recent events, live data). Do NOT use it for general knowledge you already know.

            Example:
              User: "What is the weather in Delhi right now?"
              You: [SEARCH_NEEDED]

            """.trimIndent()
        )
        sb.append("\n")

        // ── User profile ──────────────────────────────────────────────────────
        val profileParts = buildList {
            userProfile["name"]?.takeIf { it.isNotBlank() }?.let { add("Name: $it") }
            userProfile["location"]?.takeIf { it.isNotBlank() }?.let { add("Location: $it") }
            userProfile["dob"]?.takeIf { it.isNotBlank() }?.let { add("Date of Birth: $it") }
            userProfile["bio"]?.takeIf { it.isNotBlank() }?.let { add("Bio: $it") }
        }
        if (profileParts.isNotEmpty()) {
            sb.append("## User Profile\n")
            profileParts.forEach { sb.append("- $it\n") }
            sb.append("\n")
        }

        // ── Relevant memories ─────────────────────────────────────────────────
        if (memories.isNotEmpty()) {
            sb.append("## Relevant Context from Memory\n")
            memories.forEach { memory ->
                sb.append("- ${memory.content}\n")
            }
            sb.append("\n")
        }

        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\nUnderstood. I will follow the tool protocol and user profile above.\n<end_of_turn>\n")

        // ── Conversation history (sliding window, oldest → newest) ────────────
        val historyWindow = recentHistory
            .dropLast(1)                          // exclude the current query (added below)
            .takeLast(maxHistoryMessages)
        historyWindow.forEach { msg ->
            val role = if (msg.isUser) "user" else "model"
            sb.append("<start_of_turn>$role\n${msg.text.trim()}<end_of_turn>\n")
        }

        // ── Current query ─────────────────────────────────────────────────────
        sb.append("<start_of_turn>user\n${query.trim()}<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")

        return sb.toString()
    }
}
