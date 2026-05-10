package com.example.llmapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmapp.core.history.ChatHistoryManager
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.state.ChatIntent
import com.example.llmapp.ui.state.ChatUiState
import com.example.llmapp.ui.state.StreamingSegment
import com.example.llmapp.ui.state.StreamingState
import com.example.llmapp.ui.state.VoiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parses visible streaming text into [StreamingSegment] list.
 * Pure function — cheap enough to call on every 40ms token batch.
 *
 * Rules:
 * - Consecutive lines starting with `|` form a [StreamingSegment.Table].
 * - Each completed pipe-line (terminated by \n) is a separate element in
 *   [StreamingSegment.Table.committedRows] — true per-row state granularity.
 * - The currently-being-typed incomplete last pipe-line is [StreamingSegment.Table.partialRow].
 * - Everything else is [StreamingSegment.Prose] with partial last non-pipe
 *   lines included (prose streams character-by-character).
 */
private fun parseStreamingSegments(text: String): List<StreamingSegment> {
    val result = mutableListOf<StreamingSegment>()
    val lines = text.split("\n")
    val endsWithNewline = text.endsWith("\n")
    var proseBuffer = StringBuilder()
    val tableRows = mutableListOf<String>()
    var inTable = false
    var i = 0

    fun flushProse() {
        val s = proseBuffer.toString().trimEnd('\n')
        if (s.isNotBlank()) result.add(StreamingSegment.Prose(s))
        proseBuffer = StringBuilder()
    }
    fun flushTable(partialRow: String = "") {
        if (tableRows.isNotEmpty() || partialRow.isNotBlank()) {
            result.add(StreamingSegment.Table(tableRows.toList(), partialRow))
            tableRows.clear()
        }
        inTable = false
    }

    while (i < lines.size) {
        val line = lines[i]
        val isLastLine = i == lines.size - 1
        val isPartial = isLastLine && !endsWithNewline
        val isPipe = line.trimStart().startsWith("|")

        when {
            isPipe && !isPartial -> {
                // Complete pipe-line — one new row committed
                if (!inTable) { flushProse(); inTable = true }
                tableRows.add(line.trim())
            }
            isPipe && isPartial -> {
                // Partial pipe-line being typed right now
                if (!inTable) flushProse()
                flushTable(partialRow = line.trim())
            }
            !isPipe -> {
                if (inTable) flushTable()
                if (!isPartial) proseBuffer.append(line).append("\n")
                else proseBuffer.append(line) // partial prose — show as-is
            }
        }
        i++
    }
    if (inTable) flushTable()
    flushProse()
    return result.ifEmpty { listOf(StreamingSegment.Prose(text)) }
}

/**
 * Agent state machine states:
 *  IDLE              – not running
 *  GENERATING        – LLM is streaming a response (initial attempt or final synthesis)
 *  SEARCHING         – first LLM stream is draining after sentinel was detected
 *  GENERATING_QUERY  – LLM is generating an optimised web search query
 *  WAITING_FOR_SEARCH – query generated, web search running, waiting for results
 *  RESUMING          – deprecated bridge state
 */
private enum class AgentPhase {
    IDLE,
    GENERATING,
    SEARCHING,
    GENERATING_QUERY,
    WAITING_FOR_SEARCH,
    RESUMING
}

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Maximum messages kept in RAM for the UI. Older messages are evicted;
     *  full history is always preserved on disk. */
    private val MAX_MESSAGES_IN_RAM = 200

    /** Full, unbounded message list — used ONLY for saving to disk.
     *  The UI always reads the RAM-capped _uiState.messages. */
    private val allMessages = mutableListOf<ChatMessage>()

    // ── Isolated high-frequency streaming state ───────────────────────────────
    // Only the live generating bubble subscribes to this, so the stable
    // message list never recomposes during token streaming.
    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private val _themePreference = MutableStateFlow("System")
    val themePreference: StateFlow<String> = _themePreference.asStateFlow()

    var settingsManager: SettingsManager? = null
        set(value) {
            field = value
            value?.let { _themePreference.value = it.themePreference }
        }

    var historyManager: ChatHistoryManager? = null
        set(value) {
            field = value
            refreshSessions()
        }

    private val _sessionList = MutableStateFlow<List<String>>(emptyList())
    val sessionList: StateFlow<List<String>> = _sessionList.asStateFlow()

    fun refreshSessions() {
        viewModelScope.launch {
            _sessionList.value = withContext(Dispatchers.IO) {
                historyManager?.getSessionIds()?.toList()?.sortedDescending() ?: emptyList()
            }
        }
    }

    private var currentSessionId: String = System.currentTimeMillis().toString()

    // ── Agent state machine ───────────────────────────────────────────────────
    @Volatile private var agentPhase = AgentPhase.IDLE

    // When a <action> is detected mid-stream we store its details here.
    @Volatile private var pendingAction: AgentAction? = null

    // When the web search returns we store the result here.
    private data class SearchResult(val llmText: String, val uiText: String)
    @Volatile private var pendingSearchResult: SearchResult? = null

    // Store the context so we can attach it to the final message's history
    @Volatile private var lastSearchResultContext: String? = null

    // The original user query — kept so we can use it for search if needed
    @Volatile private var currentUserQuery: String = ""

    /** Accumulates tokens during the GENERATING_QUERY phase. */
    private val queryGenBuffer = StringBuilder()

    /**
     * Fix 7: Explicit guard — prevents re-triggering web search during the synthesis
     * phase of the same turn. Reset to false on each new user message.
     */
    @Volatile private var searchPerformedThisTurn = false

    /** Callback for VoiceManager – only visible text is forwarded. */
    var onNewToken: ((token: String, done: Boolean) -> Unit)? = null

    var llmInferenceManager: LlmInferenceManager? = null
        set(value) {
            field = value
            value?.let { manager ->
                viewModelScope.launch {
                    var lastUiUpdateTime = 0L
                    val pendingTokens = java.lang.StringBuilder()
                    manager.outputFlow.collect { (token, done) -> 
                        pendingTokens.append(token)
                        val now = System.currentTimeMillis()
                        if (done || now - lastUiUpdateTime > 40) {
                            handleToken(pendingTokens.toString(), done)
                            pendingTokens.clear()
                            lastUiUpdateTime = now
                        }
                    }
                }
            }
        }

    // ── Token handler ─────────────────────────────────────────────────────────
    // Sentinel the LLM is instructed to output when it needs a web search.
    private val SEARCH_SENTINEL = "[SEARCH_NEEDED]"

    private data class ParsedContent(
        val thoughts: List<String>,
        val visibleText: String,
        val raw: String
    )

    private fun parseStreamContent(raw: String): ParsedContent {
        val thoughts = mutableListOf<String>()
        val visibleText = StringBuilder()
        var currentIdx = 0

        while (currentIdx < raw.length) {
            val startTagIdx = raw.indexOf("<thought>", currentIdx)
            if (startTagIdx != -1) {
                // Add text before the tag to visible content
                visibleText.append(raw.substring(currentIdx, startTagIdx))
                
                val endTagIdx = raw.indexOf("</thought>", startTagIdx + 9)
                if (endTagIdx != -1) {
                    val thoughtContent = raw.substring(startTagIdx + 9, endTagIdx).trim()
                    if (thoughtContent.isNotBlank()) thoughts.add(thoughtContent)
                    currentIdx = endTagIdx + 10
                } else {
                    // Unclosed thought (currently streaming)
                    val partialThought = raw.substring(startTagIdx + 9).trim()
                    if (partialThought.isNotBlank()) thoughts.add(partialThought)
                    currentIdx = raw.length
                }
            } else {
                // No more thought tags, add remainder to visible content
                visibleText.append(raw.substring(currentIdx))
                break
            }
        }

        var cleanVisible = visibleText.toString().replace(SEARCH_SENTINEL, "")
        // If it still contains unclosed tags at the very end, don't show them
        cleanVisible = cleanVisible.replace("<thought>", "").trimStart()
        
        return ParsedContent(thoughts, cleanVisible, raw)
    }

    private fun handleToken(token: String, done: Boolean) {
        when (agentPhase) {
            AgentPhase.IDLE -> {
                /* spurious token – ignore */
            }
            AgentPhase.RESUMING -> {
                /* ignore overlapping old stream */
            }
            AgentPhase.SEARCHING -> {
                // LLM engine is draining the first stream (the one that output the sentinel).
                // Wait for done=true, then ask the LLM to generate an optimised search query.
                if (done) {
                    agentPhase = AgentPhase.GENERATING_QUERY
                    queryGenBuffer.clear()
                    val queryPrompt = buildQueryGenerationPrompt(currentUserQuery)
                    try {
                        llmInferenceManager?.generateResponseAsync(queryPrompt)
                    } catch (e: Exception) {
                        agentPhase = AgentPhase.IDLE
                        _uiState.update { it.copy(isGenerating = false, errorMessage = e.message) }
                    }
                }
            }
            AgentPhase.GENERATING_QUERY -> {
                // Silently collect the LLM's optimised query (not shown in UI).
                queryGenBuffer.append(token)
                if (done) {
                    // Clean up: take first non-blank line, strip quotes, cap length
                    val rawQuery = queryGenBuffer.toString()
                    val optimizedQuery = rawQuery
                        .lines()
                        // Skip blank lines and any line that still has placeholder brackets
                        .firstOrNull { line ->
                            line.isNotBlank() && !line.contains("[") && !line.contains("]")
                        }
                        ?.trim()
                        // Strip a leading "Query:" prefix if the LLM echoed the prompt format
                        ?.removePrefix("Query:")
                        ?.trim()
                        // Strip surrounding quotes
                        ?.removeSurrounding("\"")
                        ?.removeSurrounding("'")
                        ?.take(250)
                        // Fallback to user's original question if parsing fails
                        ?: currentUserQuery

                    // Update the displayed search action with the LLM-generated query
                    val currentActions = _streamingState.value.actions.toMutableList()
                    if (currentActions.isNotEmpty()) {
                        currentActions[currentActions.lastIndex] =
                            currentActions.last().copy(query = optimizedQuery)
                    }
                    _streamingState.value = _streamingState.value.copy(actions = currentActions)

                    // Now kick off the web search with the optimised query
                    agentPhase = AgentPhase.WAITING_FOR_SEARCH
                    performWebSearch(optimizedQuery)
                }
            }
            AgentPhase.WAITING_FOR_SEARCH -> {
                // Engine is idle, waiting for search results. Ignore tokens.
            }
            AgentPhase.GENERATING -> {
                var shouldSave = false
                var messagesToSave: List<ChatMessage> = emptyList()

                _uiState.update { state ->
                    val newRaw = _streamingState.value.rawContent + token
                    val parsed = parseStreamContent(newRaw)

                    // ── Sentinel & Refusal detection ────────────────────────────────
                    // Fix 1: Removed broad phrases ("as an AI", "I am an AI") that caused
                    // false-positive search triggers on perfectly answerable responses.
                    // Only catch genuine internet-access refusals now.
                    val refusalPhrases = listOf(
                        "I do not have access to the internet",
                        "I cannot access the internet",
                        "I don't have access to the internet",
                        "I cannot browse",
                        "I don't have real-time",
                        "I do not have real-time",
                        "SEARCH_NEEDED"
                    )

                    val isRefusal = refusalPhrases.any { newRaw.contains(it, ignoreCase = true) }

                    // Fix 7: Never re-trigger search if we already searched this turn
                    if (!searchPerformedThisTurn &&
                        pendingAction == null &&
                        (newRaw.contains(SEARCH_SENTINEL) || isRefusal) &&
                        _streamingState.value.actions.isEmpty()
                    ) {
                        val query = currentUserQuery
                        agentPhase = AgentPhase.SEARCHING
                        searchPerformedThisTurn = true  // Fix 7: block re-trigger for rest of this turn
                        pendingAction = AgentAction("WebSearch", query)
                        val searchAction = AgentAction("WebSearch", query)

                        _streamingState.value = StreamingState(
                            rawContent = "",
                            visibleText = "",
                            thoughts = emptyList(),
                            actions = listOf(searchAction)
                        )
                        return@update state
                    }

                    // ── Forward visible answer text to TTS ──────────────────────────
                    val displayText = parsed.visibleText
                    val currentTtsText = _streamingState.value.visibleText
                    
                    val newTtsText = if (displayText.length > currentTtsText.length) {
                        displayText.substring(currentTtsText.length)
                    } else ""

                    if (newTtsText.isNotEmpty()) {
                        // Clean markdown for TTS
                        val cleanTts = newTtsText.replace(Regex("[#*`_~]"), "")
                        if (cleanTts.isNotBlank()) onNewToken?.invoke(cleanTts, done)
                    } else if (done && displayText.isNotEmpty()) {
                        onNewToken?.invoke("", true)
                    }

                    // ── Generation complete ──────────────────────────────────────────
                    if (done) {
                        agentPhase = AgentPhase.IDLE
                        val currentStreaming = _streamingState.value

                        val finalRawContent = if (lastSearchResultContext != null) {
                            "$SEARCH_SENTINEL<end_of_turn>\n<start_of_turn>user\nI have searched the internet for you. Here are the search results:\n\n$lastSearchResultContext\n<end_of_turn>\n<start_of_turn>model\n$newRaw"
                        } else newRaw
                        
                        lastSearchResultContext = null

                        val finalMsg = ChatMessage(
                            text = displayText,
                            isUser = false,
                            thoughts = parsed.thoughts,
                            actions = currentStreaming.actions,
                            rawContent = finalRawContent
                        )
                        val updatedMessages = (state.messages + finalMsg).takeLast(MAX_MESSAGES_IN_RAM)
                        allMessages.add(finalMsg) // always append to full list for saving
                        shouldSave = true
                        messagesToSave = allMessages.toList()

                        // Reset streaming state first so bubble disappears atomically
                        _streamingState.value = StreamingState()

                        state.copy(
                            messages = updatedMessages,
                            isGenerating = false
                        )
                    } else {
                        // Update ONLY streaming state — main uiState.messages untouched
                        _streamingState.value = StreamingState(
                            rawContent = newRaw,
                            visibleText = displayText,
                            segments = parseStreamingSegments(displayText),
                            thoughts = parsed.thoughts,
                            actions = _streamingState.value.actions
                        )
                        // Only flip isGenerating once at the start, not every token
                        if (!state.isGenerating) state.copy(isGenerating = true) else state
                    }
                }

                if (shouldSave) {
                    viewModelScope.launch {
                        historyManager?.saveSession(currentSessionId, messagesToSave)
                        refreshSessions()
                    }
                }
                // NOTE: We no longer trigger performWebSearch here.
                // The search is now started from the GENERATING_QUERY done handler
                // after the LLM generates an optimised search query.
            }
        }
    }

    // ── Web search helpers ────────────────────────────────────────────────────
    private fun performWebSearch(query: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val skill = com.example.llmapp.core.skills.WebSearchSkill()
            val (uiText, llmText) = skill.search(query)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                storeObservation(llmText, uiText)
            }
        }
    }

    private fun storeObservation(llmText: String, uiText: String?) {
        // Update the last action with displayable sources in the streaming state
        val currentActions = _streamingState.value.actions.toMutableList()
        if (currentActions.isNotEmpty() && uiText != null) {
            currentActions[currentActions.lastIndex] =
                    currentActions.last().copy(uiSources = uiText)
        }
        _streamingState.value = _streamingState.value.copy(actions = currentActions)

        // Always store the result so the SEARCHING done handler can pick it up.
        pendingSearchResult = SearchResult(llmText, uiText ?: "")
        lastSearchResultContext = llmText
        pendingAction = null

        // If the engine was ALREADY done (WAITING_FOR_SEARCH), trigger synthesis now.
        if (agentPhase == AgentPhase.WAITING_FOR_SEARCH) {
            agentPhase = AgentPhase.GENERATING
            pendingSearchResult = null
            val prompt = buildPromptWithContext(currentUserQuery, llmText)
            try {
                llmInferenceManager?.generateResponseAsync(prompt)
            } catch (e: Exception) {
                agentPhase = AgentPhase.IDLE
                _uiState.update { it.copy(isGenerating = false, errorMessage = e.message) }
            }
        }
    }

    // ── Intent handler ────────────────────────────────────────────────────────
    fun processIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> handleSendMessage(intent.text)
            is ChatIntent.ClearHistory -> {
                _uiState.update { it.copy(messages = emptyList()) }
                allMessages.clear()
                currentSessionId = System.currentTimeMillis().toString()
            }
            is ChatIntent.LoadModel -> {
                _uiState.update {
                    it.copy(
                            status = "Loading model via LiteRT...",
                            isLoadingModel = true,
                            errorMessage = null
                    )
                }
            }
            is ChatIntent.ModelLoaded -> {
                _uiState.update { it.copy(status = "Ready", isLoadingModel = false, activeBackend = intent.backend) }
            }
            is ChatIntent.SetError -> {
                agentPhase = AgentPhase.IDLE
                _uiState.update {
                    it.copy(
                            errorMessage = intent.message,
                            isGenerating = false,
                            isLoadingModel = false
                    )
                }
            }
            is ChatIntent.RestoreSession -> {
                viewModelScope.launch {
                    val messages = withContext(Dispatchers.IO) {
                        historyManager?.loadSession(intent.sessionId) ?: emptyList()
                    }
                    currentSessionId = intent.sessionId
                    allMessages.clear()
                    allMessages.addAll(messages)
                    // Show only last MAX_MESSAGES_IN_RAM in the UI
                    _uiState.update { it.copy(messages = messages.takeLast(MAX_MESSAGES_IN_RAM), errorMessage = null) }
                }
            }
            is ChatIntent.ActivateVoiceMode -> {
                _uiState.update {
                    it.copy(isVoiceModeActive = true, voiceState = VoiceState.LISTENING)
                }
            }
            is ChatIntent.DeactivateVoiceMode -> {
                _uiState.update {
                    it.copy(
                            isVoiceModeActive = false,
                            voiceState = VoiceState.IDLE,
                            partialTranscript = ""
                    )
                }
            }
            is ChatIntent.SetVoiceState -> {
                _uiState.update { it.copy(voiceState = intent.state) }
            }
            is ChatIntent.SetPartialTranscript -> {
                _uiState.update { it.copy(partialTranscript = intent.text) }
            }
            is ChatIntent.DeleteSession -> {
                viewModelScope.launch {
                    historyManager?.deleteSession(intent.sessionId)
                    refreshSessions()
                    if (currentSessionId == intent.sessionId) {
                        _uiState.update { it.copy(messages = emptyList()) }
                        currentSessionId = System.currentTimeMillis().toString()
                    }
                }
            }
            is ChatIntent.StopGeneration -> {
                if (_uiState.value.isGenerating) {
                    agentPhase = AgentPhase.IDLE
                    pendingAction = null
                    pendingSearchResult = null
                    searchPerformedThisTurn = false
                    queryGenBuffer.clear()
                    _streamingState.value = StreamingState()
                    _uiState.update {
                        it.copy(
                                isGenerating = false,
                                status = "Stopped"
                        )
                    }
                }
            }
        }
    }

    private fun handleSendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isGenerating) return

        agentPhase = AgentPhase.GENERATING
        pendingAction = null
        pendingSearchResult = null
        searchPerformedThisTurn = false  // Fix 7: reset for each new user turn
        currentUserQuery = text

        val userMessage = ChatMessage(text = text, isUser = true)
        allMessages.add(userMessage) // track in full list
        _streamingState.value = StreamingState() // reset before new generation
        _uiState.update { state ->
            state.copy(
                    messages = (state.messages + listOf(userMessage)).takeLast(MAX_MESSAGES_IN_RAM),
                    isGenerating = true,
                    errorMessage = null
            )
        }

        val modelPrompt =
                try {
                    buildPromptForMessage(text)
                } catch (e: Exception) {
                    agentPhase = AgentPhase.IDLE
                    _uiState.update {
                        it.copy(isGenerating = false, errorMessage = e.message ?: "Unknown error")
                    }
                    return
                }
        try {
            llmInferenceManager?.generateResponseAsync(modelPrompt)
        } catch (e: Exception) {
            agentPhase = AgentPhase.IDLE
            _uiState.update {
                it.copy(isGenerating = false, errorMessage = e.message ?: "Unknown error")
            }
        }
    }

    private fun buildPromptWithContext(userText: String, searchContext: String): String {
        val history = _uiState.value.messages
        val systemPrompt = buildSystemPrompt()
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n")
        sb.append(systemPrompt)
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\nSure, I am ready to help!<end_of_turn>\n")

        val limit = settingsManager?.contextLimit ?: 10
        val msgCap = perMessageCharCap()
        val messagesToInclude = history.dropLast(1).takeLast(limit)

        for (msg in messagesToInclude) {
            val content = msg.text.take(msgCap)
            if (content.isBlank()) continue
            if (msg.isUser) sb.append("<start_of_turn>user\n$content<end_of_turn>\n")
            else sb.append("<start_of_turn>model\n$content<end_of_turn>\n")
        }

        sb.append("<start_of_turn>user\n")
        sb.append("The following are raw web search results retrieved to help answer the user's question.\n")
        sb.append("The USER CANNOT SEE these search results — only your final reply is shown to them.\n\n")
        sb.append("=== SEARCH RESULTS START ===\n")
        // Fix 2: Cap searchContext so it never overflows the model's context window.
        // Budget: maxTokens * 3 chars (~75% of output budget as search context budget).
        val searchContextCap = (settingsManager?.maxTokens ?: 1024) * 3
        sb.append(searchContext.take(searchContextCap))
        sb.append("\n=== SEARCH RESULTS END ===\n\n")
        sb.append("USER'S QUESTION: $userText\n\n")
        sb.append("YOUR TASK — synthesise a helpful answer following these rules:\n")
        sb.append("1. READ the search results carefully and EXTRACT only the information that directly answers the user's question.\n")
        sb.append("2. PRESENT the answer directly to the user in clear, natural language — do NOT mention 'search results', 'the data above', or 'according to the results'.\n")
        sb.append("3. If the results contain specific facts (numbers, dates, names, prices, scores), include them accurately.\n")
        sb.append("4. If multiple sources agree, state the fact confidently. If they disagree, note the range.\n")
        sb.append("5. Keep the answer concise and relevant. Do NOT dump all the search text — extract only what matters.\n")
        sb.append("6. If the search results do not contain enough information to answer the question, say so honestly and share what you do know.\n")
        sb.append("7. Do NOT output the [SEARCH_NEEDED] token — the search is already complete.\n")
        sb.append("8. Format your response using Markdown for clarity (bold key facts, use lists if needed).\n")
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")

        return sb.toString()
    }

    private fun buildPromptForMessage(userText: String): String {
        val history = _uiState.value.messages
        return buildPromptFromHistory(history, userText)
    }

    /**
     * Builds a minimal prompt that asks the LLM to produce a single ready-to-use web
     * search query for [userQuestion].
     *
     * Injects user profile data and the last 3 conversation turns so the LLM can
     * substitute vague references ("my city", "near me", "today") with concrete values
     * instead of outputting unhelpful placeholders like [Your Location].
     */
    private fun buildQueryGenerationPrompt(userQuestion: String): String {
        val mgr = settingsManager
        // Gather profile fields that are actually set
        val profileParts = buildList {
            mgr?.userName?.takeIf { it.isNotBlank() }?.let { add("Name: $it") }
            mgr?.userLocation?.takeIf { it.isNotBlank() }?.let { add("Location: $it") }
            mgr?.userDob?.takeIf { it.isNotBlank() }?.let { add("Date of Birth: $it") }
            mgr?.userBio?.takeIf { it.isNotBlank() }?.let { add("Bio: $it") }
        }

        // Recent conversation context (last 3 turns) to resolve references like "it", "that"
        val recentHistory = _uiState.value.messages
            .takeLast(6)  // last 3 user+assistant pairs
            .joinToString("\n") { msg ->
                val role = if (msg.isUser) "User" else "Assistant"
                "$role: ${msg.text.take(300)}"
            }

        return buildString {
            append("<start_of_turn>user\n")
            append("Your task: convert the user's question into a single, literal, ready-to-paste web search query.\n\n")

            if (profileParts.isNotEmpty()) {
                append("USER PROFILE (use this to fill in any personal or location-specific details):\n")
                profileParts.forEach { append("- $it\n") }
                append("\n")
            }

            if (recentHistory.isNotBlank()) {
                append("RECENT CONVERSATION (use this to resolve pronouns like 'it', 'that', 'there'):\n")
                append(recentHistory)
                append("\n\n")
            }

            append("STRICT RULES:\n")
            append("1. Output ONLY the search query. Nothing else — no explanation, no intro sentence.\n")
            append("2. The query MUST be concrete and literal.\n")
            append("   - If the user asks about their location, use their actual location from the profile.\n")
            append("   - If the user references something from recent conversation, include it explicitly.\n")
            append("   - NEVER output placeholders like [city], [your location], [date], [name].\n")
            append("3. Do NOT wrap the query in quotes.\n")
            append("4. Keep it under 10 words.\n\n")

            append("EXAMPLES:\n")
            append("Profile Location: Mumbai | Question: What's the weather like today?\n")
            append("Query: Mumbai weather today\n\n")
            append("Profile Location: Delhi | Question: Best restaurants near me?\n")
            append("Query: best restaurants in Delhi 2025\n\n")
            append("Question: What are the latest IPL scores?\n")
            append("Query: IPL 2025 latest match scores today\n\n")
            append("Question: Who won the US election?\n")
            append("Query: US election 2024 winner results\n\n")
            append("Question: What is the price of Bitcoin right now?\n")
            append("Query: Bitcoin price USD live\n\n")
            append("Now generate the query for this question:\n")
            append("Question: $userQuestion\n")
            append("Query:")
            append("<end_of_turn>\n")
            append("<start_of_turn>model\n")
        }
    }

    /**
     * Returns a safe per-message character cap based on the user's maxTokens setting.
     *
     * Fix 4+6: Old formula used 50% of maxTokens with no overhead accounting,
     * resulting in only ~51 tokens per message (barely a sentence).
     *
     * New formula:
     *  - Fixed overhead (system prompt + sentinel rules + 3 few-shots) ≈ 420 tokens = 1680 chars
     *  - Total context budget ≈ maxTokens * 4 chars (conservative: assumes context window = 4x output)
     *  - Available for history = total - overhead, divided by number of messages
     *  - Clamped to [400, 8000] chars as safety bounds
     */
    private fun perMessageCharCap(): Int {
        val maxTokens = settingsManager?.maxTokens ?: 1024
        val contextLimit = (settingsManager?.contextLimit ?: 10).coerceAtLeast(1)
        val FIXED_OVERHEAD_CHARS = 1680 // ~420 tokens of system/sentinel/few-shot boilerplate
        val totalBudgetChars = maxTokens * 4
        val availableForHistory = (totalBudgetChars - FIXED_OVERHEAD_CHARS).coerceAtLeast(400)
        val cap = availableForHistory / contextLimit
        return cap.coerceIn(400, 8000)
    }

    fun updateTheme(theme: String) {
        settingsManager?.themePreference = theme
        _themePreference.value = theme
    }

    private fun buildSystemPrompt(): String {
        val manager = settingsManager ?: return "You are a helpful AI assistant."
        val userSystemPrompt = manager.systemPrompt
        val profile = StringBuilder()

        profile.append("You are a helpful AI assistant.")
        if (userSystemPrompt.isNotBlank()) {
            profile.append("\n$userSystemPrompt")
        }

        val name = manager.userName
        val dob = manager.userDob
        val loc = manager.userLocation
        val bio = manager.userBio

        if (name.isNotBlank() || dob.isNotBlank() || loc.isNotBlank() || bio.isNotBlank()) {
            profile.append("\n\nUSER INFORMATION:")
            if (name.isNotBlank()) profile.append("\n- Name: $name")
            if (dob.isNotBlank()) profile.append("\n- Date of Birth: $dob")
            if (loc.isNotBlank()) profile.append("\n- Location: $loc")
            if (bio.isNotBlank()) profile.append("\n- Interests/Bio: $bio")
            profile.append(
                    "\n\nUse this information to provide personalized and relevant responses to the user."
            )
        }

        val currentDateTime =
                SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
                        .format(Date())
        profile.append("\n\nCURRENT CONTEXT:")
        profile.append("\n- Date and Time: $currentDateTime")

        profile.append("\n\nFORMATTING INSTRUCTIONS:")
        profile.append("\n- Use professional Markdown for all responses.")
        profile.append("\n- Use **bold text** for emphasis or key terms.")
        profile.append(
                "\n- Use bullet points or numbered lists for multi-step information or lists."
        )
        profile.append("\n- Use ### Headers or ## Sections to organize complex or long responses.")
        profile.append(
                "\n- Use Markdown Tables if you need to present comparative data or structured lists."
        )
        profile.append("\n- Use `inline code` for technical terms or variables.")
        profile.append(
                "\n- Keep responses concise but visually well-structured for a mobile screen."
        )

        return profile.toString()
    }

    private fun buildPromptFromHistory(
            messages: List<ChatMessage>,
            pendingUserText: String = ""
    ): String {
        val systemPrompt = buildSystemPrompt()
        val sb = StringBuilder()

        sb.append("<start_of_turn>user\n")
        sb.append(systemPrompt)
        sb.append("\n\nIMPORTANT RULE:\n")
        sb.append(
                "If you cannot answer a question from your own knowledge (e.g. current weather, today's news, live scores, real-time prices, recent events), you MUST output ONLY this exact token and nothing else:\n"
        )
        sb.append("$SEARCH_SENTINEL\n\n")
        sb.append("Do NOT say \"I don't have access\" or \"I cannot search the internet\".\n")
        sb.append("Do NOT try to answer if you don't know.\n")
        sb.append("Just output $SEARCH_SENTINEL and stop.\n\n")
        sb.append("If you CAN answer from your knowledge, answer normally and helpfully.\n")
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        sb.append(
                "Understood. If I don't have the information, I will output $SEARCH_SENTINEL and stop."
        )
        sb.append("<end_of_turn>\n")

        // Few-shots
        sb.append("<start_of_turn>user\nWhat is the weather in Delhi right now?<end_of_turn>\n")
        sb.append("<start_of_turn>model\n$SEARCH_SENTINEL<end_of_turn>\n")
        sb.append("<start_of_turn>user\nWhat is the capital of France?<end_of_turn>\n")
        sb.append("<start_of_turn>model\nThe capital of France is Paris.<end_of_turn>\n")
        sb.append("<start_of_turn>user\nWhat are the latest cricket scores?<end_of_turn>\n")
        sb.append("<start_of_turn>model\n$SEARCH_SENTINEL<end_of_turn>\n")

        val limit = settingsManager?.contextLimit ?: 10
        val msgCap = perMessageCharCap()
        val historyToInclude = messages.dropLast(1).takeLast(limit)

        for (msg in historyToInclude) {
            // Use display text only — rawContent can contain full web search results.
            // Cap is derived dynamically from the maxTokens setting in Settings.
            val contentToUse = msg.text.take(msgCap)
            if (contentToUse.isBlank()) continue
            if (msg.isUser) sb.append("<start_of_turn>user\n${contentToUse}<end_of_turn>\n")
            else sb.append("<start_of_turn>model\n${contentToUse}<end_of_turn>\n")
        }

        if (pendingUserText.isNotBlank()) {
            sb.append("<start_of_turn>user\n${pendingUserText}<end_of_turn>\n")
        }

        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        llmInferenceManager?.close()
    }
}
