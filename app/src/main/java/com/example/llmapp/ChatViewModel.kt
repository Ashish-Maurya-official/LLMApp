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
 * Agent state machine states: IDLE – not running GENERATING – LLM is streaming tokens (initial or
 * synthesis) SEARCHING – LLM is still draining its first stream, search is running
 * WAITING_FOR_SEARCH – LLM engine is idle/done, but search is still running RESUMING –
 * deprecated/bridge state
 */
private enum class AgentPhase {
    IDLE,
    GENERATING,
    SEARCHING,
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

    // The original user query — kept so we can use it as the search query
    @Volatile private var currentUserQuery: String = ""

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
                // LLM engine is draining its first stream (the one that output sentinel).
                // We must wait for done=true before we can safely start a new generation.
                if (done) {
                    val result = pendingSearchResult
                    if (result != null) {
                        // Search already finished! We can start synthesis immediately.
                        agentPhase = AgentPhase.GENERATING
                        pendingSearchResult = null
                        val prompt = buildPromptWithContext(currentUserQuery, result.llmText)
                        try {
                            llmInferenceManager?.generateResponseAsync(prompt)
                        } catch (e: Exception) {
                            agentPhase = AgentPhase.IDLE
                            _uiState.update {
                                it.copy(isGenerating = false, errorMessage = e.message)
                            }
                        }
                    } else {
                        // Search still running. Switch to waiting state.
                        // storeObservation will trigger synthesis when the search results arrive.
                        agentPhase = AgentPhase.WAITING_FOR_SEARCH
                    }
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
                    val refusalPhrases = listOf(
                        "I do not have access",
                        "I cannot access",
                        "I don't have access",
                        "as an AI",
                        "I am an AI",
                        "SEARCH_NEEDED"
                    )
                    
                    // Only trigger search if refusal is detected AND we haven't already searched
                    val isRefusal = refusalPhrases.any { newRaw.contains(it, ignoreCase = true) }

                    if (pendingAction == null &&
                        (newRaw.contains(SEARCH_SENTINEL) || isRefusal) &&
                        _streamingState.value.actions.isEmpty()
                    ) {
                        val query = currentUserQuery
                        agentPhase = AgentPhase.SEARCHING
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

                if (agentPhase == AgentPhase.SEARCHING) {
                    val action = pendingAction
                    if (action != null) performWebSearch(action.query)
                }
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
            // Use display text only to keep token count predictable.
            // Cap is derived from the maxTokens setting in Settings.
            val content = msg.text.take(msgCap)
            if (content.isBlank()) continue
            if (msg.isUser) sb.append("<start_of_turn>user\n$content<end_of_turn>\n")
            else sb.append("<start_of_turn>model\n$content<end_of_turn>\n")
        }

        sb.append("<start_of_turn>user\n")
        sb.append("I have searched the internet for you. Here are the search results:\n\n")
        sb.append(searchContext)
        sb.append("\n\nINSTRUCTIONS:\n")
        sb.append("1. Use the search results above to answer the user's question accurately.\n")
        sb.append("2. Answer naturally and conversationally.\n")
        sb.append(
                "3. Do NOT output the [SEARCH_NEEDED] token now, as the search is already complete.\n"
        )
        sb.append("\nUser Question: $userText")
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")

        return sb.toString()
    }

    private fun buildPromptForMessage(userText: String): String {
        val history = _uiState.value.messages
        return buildPromptFromHistory(history, userText)
    }

    /**
     * Returns a safe per-message character cap based on the user's maxTokens setting.
     *
     * Reasoning:
     *  - ~4 chars per token (English average).
     *  - We reserve 50% of the total token budget for the fixed overhead
     *    (system prompt, few-shots, current user message).
     *  - The other 50% is split equally across the context history messages.
     *  - Result is clamped to [400, 8000] chars to guard against extreme settings.
     */
    private fun perMessageCharCap(): Int {
        val maxTokens = settingsManager?.maxTokens ?: 1024
        val contextLimit = (settingsManager?.contextLimit ?: 10).coerceAtLeast(1)
        // 4 chars/token, half the budget for history, divided by number of messages
        val cap = (maxTokens * 4 / 2) / contextLimit
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
