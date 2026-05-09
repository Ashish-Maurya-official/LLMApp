package com.example.llmapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmapp.core.history.ChatHistoryManager
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.state.ChatIntent
import com.example.llmapp.ui.state.ChatUiState
import com.example.llmapp.ui.state.VoiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
            _sessionList.value =
                    historyManager?.getSessionIds()?.toList()?.sortedDescending() ?: emptyList()
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
                    manager.outputFlow.collect { (token, done) -> handleToken(token, done) }
                }
            }
        }

    // ── Token handler ─────────────────────────────────────────────────────────
    // Sentinel the LLM is instructed to output when it needs a web search.
    private val SEARCH_SENTINEL = "[SEARCH_NEEDED]"

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
                    val newRaw = state.currentGeneratingRawContent + token

                    // ── Sentinel & Refusal detection ────────────────────────────────
                    val refusalPhrases =
                            listOf(
                                    "I do not have access",
                                    "I cannot access",
                                    "I don't have access",
                                    "as an AI",
                                    "I am an AI"
                            )
                    val isRefusal = refusalPhrases.any { newRaw.contains(it, ignoreCase = true) }

                    if (pendingAction == null &&
                                    (newRaw.contains(SEARCH_SENTINEL) || isRefusal) &&
                                    state.currentActions.isEmpty()
                    ) {
                        val query = currentUserQuery
                        agentPhase = AgentPhase.SEARCHING
                        pendingAction = AgentAction("WebSearch", query)

                        return@update state.copy(
                                currentGeneratingRawContent = "", // discard sentinel/refusal
                                currentGeneratingMessage = "",
                                currentActions = listOf(AgentAction("WebSearch", query))
                        )
                    }

                    // ── Extract visible answer text ────────────────────────────────
                    val displayText = newRaw.replace(SEARCH_SENTINEL, "").trimStart()

                    // Forward only NEW text to TTS
                    val currentText = state.currentGeneratingMessage
                    val newTtsText =
                            if (displayText.length > currentText.length)
                                    displayText.substring(currentText.length)
                            else ""

                    if (newTtsText.isNotEmpty()) onNewToken?.invoke(newTtsText, done)
                    else if (done && displayText.isNotEmpty()) onNewToken?.invoke("", true)

                    // ── Generation complete ──────────────────────────────────────────
                    if (done) {
                        agentPhase = AgentPhase.IDLE

                        // Trick the LLM into thinking it successfully used the SEARCH_SENTINEL in
                        // its history!
                        val finalRawContent =
                                if (lastSearchResultContext != null) {
                                    "$SEARCH_SENTINEL<end_of_turn>\n<start_of_turn>user\nI have searched the internet for you. Here are the search results:\n\n$lastSearchResultContext\n<end_of_turn>\n<start_of_turn>model\n$newRaw"
                                } else newRaw
                        lastSearchResultContext = null

                        val finalMsg =
                                ChatMessage(
                                        text = displayText,
                                        isUser = false,
                                        thoughts = emptyList(),
                                        actions = state.currentActions,
                                        rawContent = finalRawContent
                                )
                        val updatedMessages = state.messages + finalMsg
                        shouldSave = true
                        messagesToSave = updatedMessages

                        state.copy(
                                messages = updatedMessages,
                                currentGeneratingMessage = "",
                                currentGeneratingRawContent = "",
                                currentThoughts = emptyList(),
                                currentActions = emptyList(),
                                isGenerating = false
                        )
                    } else {
                        state.copy(
                                currentGeneratingRawContent = newRaw,
                                currentGeneratingMessage = displayText,
                                isGenerating = true
                        )
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
        // Update the last action with displayable sources
        val currentActions = _uiState.value.currentActions.toMutableList()
        if (currentActions.isNotEmpty() && uiText != null) {
            currentActions[currentActions.lastIndex] =
                    currentActions.last().copy(uiSources = uiText)
        }

        _uiState.update { it.copy(currentActions = currentActions) }

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
                currentSessionId = System.currentTimeMillis().toString()
            }
            is ChatIntent.LoadModel -> {
                _uiState.update {
                    it.copy(
                            status = "Loading model via MediaPipe GPU...",
                            isLoadingModel = true,
                            errorMessage = null
                    )
                }
            }
            is ChatIntent.ModelLoaded -> {
                _uiState.update { it.copy(status = "Ready", isLoadingModel = false) }
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
                    val messages = historyManager?.loadSession(intent.sessionId) ?: emptyList()
                    currentSessionId = intent.sessionId
                    _uiState.update { it.copy(messages = messages, errorMessage = null) }
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
                    _uiState.update {
                        it.copy(
                                isGenerating = false,
                                currentGeneratingMessage = "",
                                currentGeneratingRawContent = "",
                                currentThoughts = emptyList(),
                                currentActions = emptyList(),
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

        val userMessage = ChatMessage(text, isUser = true)
        _uiState.update { state ->
            state.copy(
                    messages = state.messages + listOf(userMessage),
                    currentGeneratingMessage = "",
                    currentGeneratingRawContent = "",
                    currentThoughts = emptyList(),
                    currentActions = emptyList(),
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
        val messagesToInclude = history.dropLast(1).takeLast(limit)

        for (msg in messagesToInclude) {
            val content = if (msg.rawContent.isNotBlank()) msg.rawContent else msg.text
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

    fun updateTheme(theme: String) {
        settingsManager?.themePreference = theme
        _themePreference.value = theme
    }

    private fun buildSystemPrompt(): String {
        val manager = settingsManager ?: return "Helpful AI assistant."
        val sb = StringBuilder("You are a helpful, empathetic AI assistant. ")
        
        // Dynamic User Persona (Merged for token efficiency)
        val name = manager.userName
        val dob = manager.userDob
        val loc = manager.userLocation
        val bio = manager.userBio
        
        if (name.isNotBlank() || dob.isNotBlank() || loc.isNotBlank() || bio.isNotBlank()) {
            sb.append("\nUser: ${if(name.isNotBlank()) name else "User"}")
            val details = mutableListOf<String>()
            if (dob.isNotBlank()) details.add("born $dob")
            if (loc.isNotBlank()) details.add("in $loc")
            if (details.isNotEmpty()) sb.append(" (${details.joinToString(", ")})")
            if (bio.isNotBlank()) sb.append(". Interests: $bio")
            sb.append(". Provide personalized, human-like responses.")
        }

        if (manager.systemPrompt.isNotBlank()) sb.append("\nNotes: ${manager.systemPrompt}")
        
        val time = SimpleDateFormat("MMMM yyyy, EEE d, HH:mm", Locale.getDefault()).format(Date())
        sb.append("\nTime: $time")

        // Compressed Style Guide
        sb.append("\nStyle: Professional Markdown (headers/bold/tables). Human-like tone. No AI clichés.")
        
        return sb.toString()
    }

    private fun buildPromptFromHistory(
            messages: List<ChatMessage>,
            pendingUserText: String = ""
    ): String {
        val sb = StringBuilder()

        sb.append("<start_of_turn>user\n")
        sb.append(buildSystemPrompt())
        sb.append("\n\nRULE: If current knowledge is insufficient (weather/news/scores/real-time), output ONLY: $SEARCH_SENTINEL. No disclaimers.")
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\nUnderstood. I will use $SEARCH_SENTINEL when needed and respond naturally.<end_of_turn>\n")

        // Conditional Few-shots: Only show if history is short to save tokens
        if (messages.size < 4) {
            sb.append("<start_of_turn>user\nWhat is the weather in Delhi?<end_of_turn>\n")
            sb.append("<start_of_turn>model\n$SEARCH_SENTINEL<end_of_turn>\n")
            sb.append("<start_of_turn>user\nCapital of France?<end_of_turn>\n")
            sb.append("<start_of_turn>model\nParis.<end_of_turn>\n")
        }

        val limit = settingsManager?.contextLimit ?: 10
        val historyToInclude = messages.dropLast(1).takeLast(limit)

        for (msg in historyToInclude) {
            val contentToUse = if (msg.rawContent.isNotBlank()) msg.rawContent else msg.text
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
