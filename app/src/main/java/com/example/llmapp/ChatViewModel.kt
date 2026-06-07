package com.example.llmapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llmapp.core.history.ChatHistoryManager
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.ui.chat.state.ChatIntent
import com.example.llmapp.ui.chat.state.ChatUiState
import com.example.llmapp.ui.chat.state.StreamingSegment
import com.example.llmapp.ui.chat.state.StreamingState
import com.example.llmapp.ui.chat.state.VoiceState
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
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.File
import com.example.llmapp.core.search.SearchType
import com.example.llmapp.core.search.SearchIntentClassifier
import com.example.llmapp.core.prompts.PromptPipeline
import com.example.llmapp.core.prompts.models.PromptContext

// ---------------------------------------------------------------------------
// Streaming segment parser
// ---------------------------------------------------------------------------

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
        if (s.isNotBlank()) {
            val weatherRegex = Regex("\\[WEATHER_CARD:(\\{[\\s\\S]*?\\})\\]")
            var lastIndex = 0
            weatherRegex.findAll(s).forEach { match ->
                val textBefore = s.substring(lastIndex, match.range.first)
                if (textBefore.isNotBlank()) {
                    result.add(StreamingSegment.Prose(textBefore.trimEnd('\n')))
                }
                try {
                    val json = org.json.JSONObject(match.groupValues[1])
                    val details = com.example.llmapp.ui.chat.state.WeatherDetails(
                        location = json.optString("location", "Unknown Location"),
                        temp = json.optString("temp", "--"),
                        condition = json.optString("condition", "Unknown"),
                        humidity = json.optString("humidity").takeIf { it.isNotBlank() },
                        wind = json.optString("wind").takeIf { it.isNotBlank() },
                        high = json.optString("high").takeIf { it.isNotBlank() },
                        low = json.optString("low").takeIf { it.isNotBlank() }
                    )
                    result.add(StreamingSegment.Weather(details))
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "Failed to parse WEATHER_CARD JSON: ${e.message}")
                    result.add(StreamingSegment.Prose(match.value))
                }
                lastIndex = match.range.last + 1
            }
            if (lastIndex < s.length) {
                val remaining = s.substring(lastIndex)
                if (remaining.isNotBlank()) {
                    result.add(StreamingSegment.Prose(remaining.trimEnd('\n')))
                }
            }
        }
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
            isPipe && !isPartial -> { if (!inTable) { flushProse(); inTable = true }; tableRows.add(line.trim()) }
            isPipe && isPartial  -> { if (!inTable) flushProse(); flushTable(partialRow = line.trim()) }
            !isPipe -> {
                if (inTable) flushTable()
                if (!isPartial) proseBuffer.append(line).append("\n") else proseBuffer.append(line)
            }
        }
        i++
    }
    if (inTable) flushTable()
    flushProse()
    return result.ifEmpty { listOf(StreamingSegment.Prose(text)) }
}

// ---------------------------------------------------------------------------
// Agent phase state machine (Deprecating for CognitiveTaskScheduler)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class ChatViewModel : ViewModel() {

    // ── Core UI state ────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // ── RAM-first message list ───────────────────────────────────────────────
    private val allMessages = mutableListOf<ChatMessage>()
    private val _sessionMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val sessionMessages: StateFlow<List<ChatMessage>> = _sessionMessages.asStateFlow()

    private val MAX_CONTEXT = 200

    private fun pushMessages() { _sessionMessages.value = allMessages.toList() }

    // ── Streaming state ──────────────────────────────────────────────────────
    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    // ── Theme ────────────────────────────────────────────────────────────────
    private val _themePreference = MutableStateFlow("System")
    val themePreference: StateFlow<String> = _themePreference.asStateFlow()

    fun updateTheme(theme: String) { settingsManager?.themePreference = theme; _themePreference.value = theme }

    // ── Settings ─────────────────────────────────────────────────────────────
    var settingsManager: SettingsManager? = null
        set(value) {
            field = value
            value?.let { _themePreference.value = it.themePreference; initializeRetrieval(it) }
        }

    // ── History manager ──────────────────────────────────────────────────────
    var historyManager: ChatHistoryManager? = null
        set(value) {
            field = value
            val hMgr = value ?: return
            
            cognitiveTaskScheduler.chatDatabase = hMgr.database
            cognitiveTaskScheduler.cognitiveStateDao = hMgr.database.cognitiveStateDao()
            cognitiveTaskScheduler.snapshotDao = hMgr.database.snapshotDao()
            cognitiveTaskScheduler.chatDao = hMgr.chatDao()
            cognitiveTaskScheduler.replayTracer = com.example.llmapp.core.telemetry.ReplayTracer(hMgr.context)
            cognitiveTaskScheduler.equilibriumMonitor = com.example.llmapp.core.telemetry.EquilibriumMonitor()
            cognitiveTaskScheduler.settingsManager = settingsManager
            
            // Initialize the unified ConversationEngine (replaces both VoiceInteractionManager and VoiceManager)
            conversationEngine = com.example.llmapp.core.voice.pipeline.ConversationEngine(
                context = hMgr.context,
                scope = viewModelScope,
                onPartialTranscript = { partialText ->
                    processIntent(ChatIntent.SetPartialTranscript(partialText))
                },
                onTranscript = { text ->
                    if (text == "[SYSTEM_INTERCEPT_STOP]") {
                        _uiState.update { it.copy(partialTranscript = "", voiceState = VoiceState.LISTENING) }
                        cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.StopGeneration("SYSTEM_STOP"))
                    } else {
                        _uiState.update { it.copy(partialTranscript = "", voiceState = VoiceState.THINKING) }
                        handleSendMessage(text)
                    }
                },
                onStateChanged = { pipelineState ->
                    val voiceState = when (pipelineState) {
                        is com.example.llmapp.core.voice.pipeline.AudioPipelineState.Listening,
                        is com.example.llmapp.core.voice.pipeline.AudioPipelineState.Capturing  -> VoiceState.LISTENING
                        is com.example.llmapp.core.voice.pipeline.AudioPipelineState.Transcribing,
                        is com.example.llmapp.core.voice.pipeline.AudioPipelineState.Thinking   -> VoiceState.THINKING
                        is com.example.llmapp.core.voice.pipeline.AudioPipelineState.Speaking   -> VoiceState.SPEAKING
                        else -> if (_uiState.value.isVoiceModeActive) VoiceState.LISTENING else VoiceState.IDLE
                    }
                    _uiState.update { it.copy(voiceState = voiceState) }
                }
            )

            // Initialize Identity Anchor Manager
            identityAnchorManager = com.example.llmapp.core.identity.IdentityAnchorManager(hMgr.database.cognitiveStateDao())
            
            // Collect Entropy Events
            viewModelScope.launch {
                contextEntropyMonitor.entropyEvents.collect {
                    // Flush Context (Start new session)
                    val newSessionId = System.currentTimeMillis().toString()
                    val sysEvent = ChatMessage(text = "[SYSTEM: Context collapsed due to high topic entropy. Memory flushed.]", isUser = false)
                    allMessages.clear()
                    allMessages.add(sysEvent)
                    pushMessages()
                    currentSessionId = newSessionId
                }
            }

            refreshSessions()
            settingsManager?.let { initializeRetrieval(it) }
        }
        
    var conversationEngine: com.example.llmapp.core.voice.pipeline.ConversationEngine? = null
        private set

    /** Called by ChatScreen's token callback. Routes LLM tokens to the TTS pipeline. */
    fun onNewLlmToken(token: String, isDone: Boolean) {
        if (_uiState.value.isVoiceModeActive) {
            conversationEngine?.feedToken(token, isDone)
        }
    }

    // ── Session list ─────────────────────────────────────────────────────────
    private val _sessionList = MutableStateFlow<List<com.example.llmapp.core.database.SessionEntity>>(emptyList())
    val sessionList: StateFlow<List<com.example.llmapp.core.database.SessionEntity>> = _sessionList.asStateFlow()

    fun refreshSessions() {
        viewModelScope.launch {
            _sessionList.value = withContext(Dispatchers.IO) { historyManager?.getSessions() ?: emptyList() }
        }
    }

    // ── Retrieval ────────────────────────────────────────────────────────────
    private var hybridRetriever: com.example.llmapp.core.retrieval.HybridRetriever? = null
    private var embeddingManager: com.example.llmapp.core.retrieval.EmbeddingManager? = null

    private fun initializeRetrieval(settings: SettingsManager) {
        val hMgr = historyManager ?: return
        val modelPath = File(hMgr.context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "all-minilm-l6-v2.onnx").absolutePath
        val vocabPath = File(hMgr.context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "all-minilm-l6-v2-vocab.txt").absolutePath
        val em = com.example.llmapp.core.retrieval.EmbeddingManager(hMgr.context)
        if (File(modelPath).exists() && File(vocabPath).exists()) em.initialize(modelPath, vocabPath)
        embeddingManager = em
        hybridRetriever = com.example.llmapp.core.retrieval.HybridRetriever(hMgr.chatDao(), em)
        
        // Initialize MemoryAgent with the dedicated MemoryDao
        val memoryAgent = com.example.llmapp.core.memory.MemoryAgent(hMgr.database.memoryDao())
        cognitiveTaskScheduler.memoryAgent = memoryAgent
        // Wire LLM if already available
        llmInferenceManager?.let { memoryAgent.llmInferenceManager = it }

        // Initialize Evaluation Runner once Retriever is ready
        llmInferenceManager?.let { inferenceManager ->
            evaluationRunner = com.example.llmapp.core.evaluation.EvaluationRunner(hMgr.database, hybridRetriever!!, inferenceManager)
        }
    }

    var evaluationRunner: com.example.llmapp.core.evaluation.EvaluationRunner? = null
        private set

    val intentThreadManager = com.example.llmapp.core.regulation.IntentThreadManager()
    val socioCognitiveRegulator = com.example.llmapp.core.regulation.SocioCognitiveRegulator()
    val contextEntropyMonitor = com.example.llmapp.core.identity.ContextEntropyMonitor()
    
    var identityAnchorManager: com.example.llmapp.core.identity.IdentityAnchorManager? = null
        private set

    val cognitiveTaskScheduler = com.example.llmapp.core.runtime.CognitiveTaskScheduler(viewModelScope)

    // ── Web Search Orchestrator ───────────────────────────────────────────────
    var searchOrchestrator: com.example.llmapp.core.search.orchestration.SearchOrchestrator? = null
    // Tracks how many times we've asked the LLM to refine a search query for the current user message.
    // Reset to 0 on every new message. Capped at 1 to prevent infinite retry loops.
    @Volatile private var searchRetryCount: Int = 0

    // Tracks how many times we've allowed `[SEARCH_NEEDED: ...]` in a single user turn.
    @Volatile private var searchChainDepth: Int = 0

    // ── Session ID ───────────────────────────────────────────────────────────
    private var currentSessionId: String = System.currentTimeMillis().toString()

    @Volatile private var currentUserQuery: String = ""
    @Volatile private var activeDegradationLevel = com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.NORMAL
    var onNewToken: ((token: String, done: Boolean) -> Unit)? = null

    init {
        viewModelScope.launch {
            cognitiveTaskScheduler.events.collect { event ->
                handleCognitiveEvent(event)
            }
        }
    }

    var memoryConsolidator: com.example.llmapp.core.history.MemoryConsolidator? = null

    var llmInferenceManager: LlmInferenceManager? = null
        set(value) {
            field = value
            cognitiveTaskScheduler.llmInferenceManager = value
            
            // Wire MemoryAgent's LLM reference for summarization
            value?.let { cognitiveTaskScheduler.memoryAgent?.llmInferenceManager = it }
            
            // Wire EvaluationRunner and MemoryConsolidator
            historyManager?.let { hMgr ->
                hybridRetriever?.let { retriever ->
                    value?.let { inferenceManager ->
                        evaluationRunner = com.example.llmapp.core.evaluation.EvaluationRunner(hMgr.database, retriever, inferenceManager)
                        memoryConsolidator = com.example.llmapp.core.history.MemoryConsolidator(
                            viewModelScope,
                            inferenceManager,
                            hMgr.database.cognitiveStateDao(),
                            hMgr.database.memoryDao()
                        ).also { consolidator ->
                            // Invalidate MemoryAgent cache when new memories are written
                            consolidator.onMemoryWritten = { cognitiveTaskScheduler.memoryAgent?.invalidateCache() }
                        }
                    }
                }
            }
        }

    // ── Parsing ──────────────────────────────────────────────────────────────
    private data class ParsedContent(val thoughts: List<String>, val visibleText: String, val raw: String)

    private fun parseStreamContent(raw: String): ParsedContent {
        val thoughts = mutableListOf<String>()
        val visibleText = StringBuilder()
        var idx = 0
        while (idx < raw.length) {
            val start = raw.indexOf("<thought>", idx)
            if (start != -1) {
                visibleText.append(raw.substring(idx, start))
                val end = raw.indexOf("</thought>", start + 9)
                if (end != -1) { val t = raw.substring(start + 9, end).trim(); if (t.isNotBlank()) thoughts.add(t); idx = end + 10 }
                else { val t = raw.substring(start + 9).trim(); if (t.isNotBlank()) thoughts.add(t); idx = raw.length }
            } else { visibleText.append(raw.substring(idx)); break }
        }
        // Strip any form of the sentinel (with or without embedded query)
        var clean = visibleText.toString()
        val SENTINEL_REGEX = Regex("""\[SEARCH_NEEDED(?::\s*([^\]]+))?\]""")
        clean = SENTINEL_REGEX.replace(clean, "").replace("<thought>", "").trimStart()

        // 1. Fail-safe weather card filtering: only allow weather cards if the user's query is weather-related
        val lowerQuery = currentUserQuery.lowercase()
        val weatherKeywords = setOf("weather", "temperature", "temp", "forecast", "rain", "sunny", "humidity", "wind", "climate", "degree", "wheather", "wether")
        val isWeatherQuery = weatherKeywords.any { lowerQuery.contains(it) }
        if (!isWeatherQuery) {
            val weatherRegex = Regex("""\[WEATHER_CARD:(\{[[\s\S]]*?\})\]""")
            clean = weatherRegex.replace(clean, "")
        }

        // 2. Fail-safe post-processing: strip search narrator meta-phrases
        val metaPhrases = listOf(
            Regex("(?i)\\bbased on (?:the )?live search data,?\\s*"),
            Regex("(?i)\\bbased on (?:the )?search results?,?\\s*"),
            Regex("(?i)\\baccording to (?:the )?live search data,?\\s*"),
            Regex("(?i)\\baccording to (?:the )?search results?,?\\s*"),
            Regex("(?i)\\baccording to (?:the )?search,?\\s*"),
            Regex("(?i)\\bthe search results indicate (?:that )?\\s*"),
            Regex("(?i)\\bthe search results show (?:that )?\\s*"),
            Regex("(?i)\\bthe provided snippets show (?:that )?\\s*"),
            Regex("(?i)\\blive search data shows (?:that )?\\s*")
        )
        metaPhrases.forEach { regex ->
            clean = regex.replace(clean, { matchResult ->
                // Keep the bullet point indicator if it was immediately after a bullet symbol
                val matchIndex = matchResult.range.first
                if (matchIndex > 0 && clean[matchIndex - 1] == ' ') "" else ""
            })
        }

        // Capitalize the first letter of each sentence/bullet if replacement left it in lowercase
        clean = clean.split("\n").joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* ") && trimmed.length > 2 && trimmed[2].isLowerCase()) {
                val bulletPrefix = line.substring(0, line.indexOf("* ") + 2)
                val remaining = trimmed.substring(2)
                bulletPrefix + remaining.replaceFirstChar { it.uppercase() }
            } else if (trimmed.isNotEmpty() && trimmed[0].isLowerCase()) {
                val leadingSpaces = line.substring(0, line.indexOf(trimmed[0]))
                leadingSpaces + trimmed.replaceFirstChar { it.uppercase() }
            } else {
                line
            }
        }

        return ParsedContent(thoughts, clean, raw)
    }

    // ── Token handler (event bus consumer) ───────────────────────────────────
    private fun handleCognitiveEvent(event: com.example.llmapp.core.runtime.CognitiveEvent) {
        when (event) {
            is com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.TokenEmitted -> {
                val newRaw = _streamingState.value.rawContent + event.token
                val parsed = parseStreamContent(newRaw)

                // ── TTS forward ─────────────────────────────────────────────
                val prev = _streamingState.value.visibleText
                if (parsed.visibleText.length > prev.length) {
                    val newTts = parsed.visibleText.substring(prev.length).replace(Regex("[#*`_~]"), "")
                    if (newTts.isNotBlank()) {
                        onNewToken?.invoke(newTts, event.isDone)
                        onNewLlmToken(newTts, event.isDone)
                    } else if (event.isDone) {
                        onNewToken?.invoke("", true)
                        onNewLlmToken("", true)
                    }
                } else if (event.isDone) {
                    onNewToken?.invoke("", true)
                    onNewLlmToken("", true)
                }

                _streamingState.value = StreamingState(
                    rawContent = newRaw,
                    visibleText = parsed.visibleText,
                    segments = parseStreamingSegments(parsed.visibleText),
                    thoughts = parsed.thoughts,
                    actions = _streamingState.value.actions
                )
                if (!_uiState.value.isGenerating) _uiState.update { it.copy(isGenerating = true) }
            }
            
            is com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.GenerationComplete -> {
                val currentStreaming = _streamingState.value
                val parsed = parseStreamContent(currentStreaming.rawContent)

                val finalMsg = ChatMessage(
                    text = parsed.visibleText,
                    isUser = false,
                    thoughts = parsed.thoughts,
                    actions = currentStreaming.actions,
                    rawContent = currentStreaming.rawContent
                )
                allMessages.add(finalMsg)
                if (allMessages.size > MAX_CONTEXT) allMessages.removeAt(0)
                pushMessages()

                val snapshot = allMessages.toList()
                viewModelScope.launch(Dispatchers.IO) {
                    historyManager?.saveSession(currentSessionId, snapshot)
                    withContext(Dispatchers.Main) { refreshSessions() }
                }

                // Trigger Background Memory Consolidation
                if (parsed.visibleText.isNotBlank()) {
                    memoryConsolidator?.enqueueTurn(currentUserQuery, parsed.visibleText)
                }
                
                // Guarantee the voice pipeline knows the generation has fully finished
                onNewToken?.invoke("", true)
                onNewLlmToken("", true)

                _streamingState.value = StreamingState()
                _uiState.update { it.copy(isGenerating = false) }
            }

            is com.example.llmapp.core.runtime.CognitiveEvent.ToolEvent.SearchRequested -> {
                _streamingState.value = StreamingState(actions = emptyList())
                performWebSearch(event.query, event.generationId)
            }

            is com.example.llmapp.core.runtime.CognitiveEvent.SystemEvent.DegradationRequested -> {
                activeDegradationLevel = event.level
                Log.w("ChatViewModel", "Degradation Level Changed: ${event.level}")
                if (event.level == com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.HARD_RESET) {
                    processIntent(ChatIntent.ClearHistory)
                }
            }

            is com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.Error -> {
                _uiState.update { it.copy(isGenerating = false, errorMessage = event.error.message) }
            }
            
            else -> {}
        }
    }

    // handleVoiceEvent removed — ConversationEngine now manages all voice state transitions internally.

    // ── Web search ────────────────────────────────────────────────────────────
    private fun performWebSearch(query: String, genId: String) {
        if (searchChainDepth >= 2) {
            Log.w("ChatViewModel", "Max search chain depth reached. Forcing LLM to answer.")
            fallbackGenerationWithoutSearch(genId, "Max search depth reached")
            return
        }
        searchChainDepth++

        Log.d("ChatViewModel", "performWebSearch: \"$query\"")
        val intent = com.example.llmapp.core.search.SearchIntentClassifier.classify(query, allMessages)
        
        // Update UI Action Chip
        val actions = _streamingState.value.actions.toMutableList()
        actions.add(com.example.llmapp.AgentAction(
            toolName = "Web Search",
            query = query,
            uiSources = "Searching Web (${intent.type.name}): $query"
        ))
        _streamingState.value = _streamingState.value.copy(actions = actions)

        viewModelScope.launch(Dispatchers.IO) {
            val orchestrator = searchOrchestrator
            if (orchestrator != null) {
                val result = try {
                    orchestrator.resolveSearch(query, forceSearch = true)
                } catch (e: Exception) {
                    // Absolute last resort — orchestrator itself threw
                    Log.e("ChatViewModel", "Orchestrator threw unexpectedly: ${e.message}")
                    com.example.llmapp.core.search.orchestration.OrchestratorResult.Failed("Internal error")
                }

                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.example.llmapp.core.search.orchestration.OrchestratorResult.Success -> {
                            // ✅ Real results — inject into prompt and re-generate
                            Log.d("ChatViewModel", "Search success via ${result.response.providerUsed}")
                            storeObservation(result.formattedContext, result.statusMessage, genId, intent.type)
                        }
                        is com.example.llmapp.core.search.orchestration.OrchestratorResult.NoResults -> {
                            // ⚠️ Search ran but found nothing
                            Log.w("ChatViewModel", "Search returned no results for: \"${result.query}\"")
                            if (searchRetryCount < 1) {
                                // Ask the LLM to provide a better, more specific query
                                searchRetryCount++
                                Log.d("ChatViewModel", "Asking LLM to refine query (retry #$searchRetryCount)")
                                retryWithRefinedQuery(result.query, genId)
                            } else {
                                // Already retried once — use LLM internal knowledge
                                Log.w("ChatViewModel", "Retry limit reached. Falling back to internal knowledge.")
                                val noResultNote = "Web search returned no results for \"${result.query}\". " +
                                    "Answer using your internal knowledge and clearly state if you are uncertain."
                                storeObservation(noResultNote, "No results found", genId, intent.type)
                            }
                        }
                        is com.example.llmapp.core.search.orchestration.OrchestratorResult.Failed -> {
                            // ❌ Network/API failure — proceed without web context, don't poison prompt
                            Log.e("ChatViewModel", "Search failed: ${result.error}")
                            fallbackGenerationWithoutSearch(genId, reason = result.error)
                        }
                        is com.example.llmapp.core.search.orchestration.OrchestratorResult.Skipped -> {
                            // ⏭️ Classifier said no search needed — proceed normally
                            Log.d("ChatViewModel", "Search skipped: ${result.reason}")
                            fallbackGenerationWithoutSearch(genId, reason = null)
                        }
                    }
                }
            } else {
                // Fallback: legacy DuckDuckGo skill if orchestrator not wired yet
                val skill = com.example.llmapp.core.skills.WebSearchSkill()
                val (uiText, llmText) = skill.search(query)
                withContext(Dispatchers.Main) { storeObservation(llmText, uiText, genId, intent.type) }
            }
        }
    }

    /**
     * Asks the LLM to produce a better [SEARCH_NEEDED:] sentinel when the first search returned zero results.
     * Builds a targeted prompt that shows the LLM what was searched and tells it to try a different,
     * more specific query. The scheduler's sentinel detector picks up the new sentinel automatically.
     * Must be called from the Main dispatcher.
     */
    private fun retryWithRefinedQuery(failedQuery: String, genId: String) {
        Log.d("ChatViewModel", "Requesting refined query. Failed query was: \"$failedQuery\"")
        // Update action chip to show we're retrying
        val actions = _streamingState.value.actions.toMutableList()
        if (actions.isNotEmpty()) {
            actions[actions.lastIndex] = actions.last().copy(uiSources = "Refining search query…")
        }
        _streamingState.value = _streamingState.value.copy(actions = actions)

        // Signal scheduler that the current search cycle is done so it resets properly
        cognitiveTaskScheduler.emit(
            com.example.llmapp.core.runtime.CognitiveEvent.ToolEvent.SearchCompleted("", genId)
        )

        _uiState.update { it.copy(isGenerating = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val refinementPrompt = buildQueryRefinementPrompt(currentUserQuery, failedQuery)
            val newGenId = java.util.UUID.randomUUID().toString()
            cognitiveTaskScheduler.emit(
                com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.GenerationRequested(
                    currentUserQuery, refinementPrompt, newGenId
                )
            )
        }
    }

    /**
     * Builds a prompt that shows the LLM its failed query and instructs it to output
     * a new, more specific [SEARCH_NEEDED: ...] sentinel.
     */
    private fun buildQueryRefinementPrompt(userQuestion: String, failedQuery: String): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n")
        
        val mgr = settingsManager
        val customPrompt = mgr?.systemPrompt ?: ""
        val regulatoryPrompt = socioCognitiveRegulator.generateRegulatoryPrompt()
        val antiDependencyPrompt = identityAnchorManager?.checkAntiDependencyProtocol(currentUserQuery) ?: ""
        val systemPromptOverride = customPrompt + "\n" + regulatoryPrompt + "\n" + antiDependencyPrompt
        
        val ctx = com.example.llmapp.core.prompts.models.PromptContext(
            systemPromptOverride = systemPromptOverride,
            userName = mgr?.userName ?: "",
            userDob = mgr?.userDob ?: "",
            userLocation = mgr?.userLocation ?: "",
            userBio = mgr?.userBio ?: "",
            activeDegradationLevel = activeDegradationLevel,
            contextLimit = mgr?.contextLimit ?: 10,
            perMessageCap = perMessageCharCap()
        )
        sb.append(com.example.llmapp.core.prompts.builders.SystemPromptBuilder.build(ctx))
        sb.append("<end_of_turn>\n<start_of_turn>model\nUnderstood. I write precise search queries.\n<end_of_turn>\n")
        sb.append("<start_of_turn>user\n")
        sb.append("The user asked: \"$userQuestion\"\n\n")
        sb.append("You previously searched for: \"$failedQuery\" — but it returned ZERO results.\n")
        sb.append("The query was too generic or used the wrong keywords.\n\n")
        sb.append("== YOUR TASK ==\n")
        sb.append("Output a NEW, completely different, more specific [SEARCH_NEEDED: ...] query.\n")
        sb.append("Rules for the new query:\n")
        sb.append("  - Use different keywords than the failed query.\n")
        sb.append("  - Be more specific: add location, year, full name, tournament, product model.\n")
        sb.append("  - Try synonyms: e.g., if 'score' failed, try 'result' or 'match update'.\n")
        sb.append("  - If weather failed, try adding country name or nearest major city.\n")
        sb.append("  - Keep it to 4-8 words max, all keywords, no question words.\n\n")
        sb.append("Output ONLY the sentinel on its own line, nothing else:\n")
        sb.append("[SEARCH_NEEDED: <your improved query>]\n")
        sb.append("<end_of_turn>\n<start_of_turn>model\n")
        return sb.toString()
    }

    /**
     * Called when search fails, is skipped, or no results are found.
     * Continues generation WITHOUT injecting any search context, so the LLM uses its own knowledge.
     * [reason] is logged and shown in the action chip if non-null.
     * Must be called from the Main dispatcher.
     */
    private fun fallbackGenerationWithoutSearch(genId: String, reason: String?) {
        Log.d("ChatViewModel", "Continuing without web search. Reason: ${reason ?: "skipped"}")
        // Update action chip status if visible
        if (reason != null) {
            val actions = _streamingState.value.actions.toMutableList()
            if (actions.isNotEmpty()) {
                actions[actions.lastIndex] = actions.last().copy(uiSources = "Search unavailable")
            }
            _streamingState.value = _streamingState.value.copy(actions = actions)
        }
        // Signal scheduler that search is done (empty context — doesn't block pipeline)
        cognitiveTaskScheduler.emit(
            com.example.llmapp.core.runtime.CognitiveEvent.ToolEvent.SearchCompleted("", genId)
        )
        // Re-trigger generation WITHOUT web context using the standard prompt builder
        _uiState.update { it.copy(isGenerating = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val mgr = settingsManager
            val customPrompt = mgr?.systemPrompt ?: ""
            val regulatoryPrompt = socioCognitiveRegulator.generateRegulatoryPrompt()
            val antiDependencyPrompt = identityAnchorManager?.checkAntiDependencyProtocol(currentUserQuery) ?: ""
            val systemPromptOverride = customPrompt + "\n" + regulatoryPrompt + "\n" + antiDependencyPrompt

            val ctx = com.example.llmapp.core.prompts.models.PromptContext(
                systemPromptOverride = systemPromptOverride,
                userName = mgr?.userName ?: "",
                userDob = mgr?.userDob ?: "",
                userLocation = mgr?.userLocation ?: "",
                userBio = mgr?.userBio ?: "",
                activeDegradationLevel = activeDegradationLevel,
                contextLimit = mgr?.contextLimit ?: 10,
                perMessageCap = perMessageCharCap()
            )
            val fallbackPrompt = com.example.llmapp.core.prompts.PromptPipeline.buildNormalPrompt(currentUserQuery, allMessages, ctx)
            val newGenId = java.util.UUID.randomUUID().toString()
            cognitiveTaskScheduler.emit(
                com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.GenerationRequested(
                    currentUserQuery, fallbackPrompt, newGenId
                )
            )
        }
    }

    private fun storeObservation(llmText: String, uiText: String?, genId: String, searchType: com.example.llmapp.core.search.SearchType = com.example.llmapp.core.search.SearchType.NEWS) {
        // Guard: if llmText is empty/blank, treat as no-results fallback
        if (llmText.isBlank()) {
            fallbackGenerationWithoutSearch(genId, reason = null)
            return
        }
        // Update the action chip with status text
        val actions = _streamingState.value.actions.toMutableList()
        if (actions.isNotEmpty() && uiText != null) {
            actions[actions.lastIndex] = actions.last().copy(uiSources = uiText)
        }
        _streamingState.value = _streamingState.value.copy(actions = actions)

        // Let the scheduler know search is complete so it resets RETRIEVING phase → IDLE
        cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.ToolEvent.SearchCompleted(llmText, genId))

        // Re-trigger generation with web search results injected into the prompt.
        Log.d("ChatViewModel", "Web search complete. Re-generating with injected context.")
        _uiState.update { it.copy(isGenerating = true) }
        viewModelScope.launch(Dispatchers.Default) {
            val mgr = settingsManager
            val customPrompt = mgr?.systemPrompt ?: ""
            val regulatoryPrompt = socioCognitiveRegulator.generateRegulatoryPrompt()
            val antiDependencyPrompt = identityAnchorManager?.checkAntiDependencyProtocol(currentUserQuery) ?: ""
            val systemPromptOverride = customPrompt + "\n" + regulatoryPrompt + "\n" + antiDependencyPrompt

            val ctx = com.example.llmapp.core.prompts.models.PromptContext(
                systemPromptOverride = systemPromptOverride,
                userName = mgr?.userName ?: "",
                userDob = mgr?.userDob ?: "",
                userLocation = mgr?.userLocation ?: "",
                userBio = mgr?.userBio ?: "",
                activeDegradationLevel = activeDegradationLevel,
                contextLimit = mgr?.contextLimit ?: 10,
                perMessageCap = perMessageCharCap()
            )
            val promptWithContext = com.example.llmapp.core.prompts.PromptPipeline.buildPromptWithContext(
                currentUserQuery, llmText, searchType, allMessages, ctx
            )
            val newGenId = java.util.UUID.randomUUID().toString()
            cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.GenerationRequested(currentUserQuery, promptWithContext, newGenId))
        }
    }

    // ── Intent processor ──────────────────────────────────────────────────────
    fun processIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendMessage -> handleSendMessage(intent.text)

            is ChatIntent.RestoreSession -> {
                currentSessionId = intent.sessionId
                viewModelScope.launch {
                    val messages = withContext(Dispatchers.IO) { historyManager?.loadSession(intent.sessionId) ?: emptyList() }
                    allMessages.clear(); allMessages.addAll(messages); pushMessages()
                    _uiState.update { it.copy(isGenerating = false) }
                }
            }

            is ChatIntent.DeleteSession -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { historyManager?.deleteSession(intent.sessionId) }
                    refreshSessions()
                    if (currentSessionId == intent.sessionId) { allMessages.clear(); pushMessages(); currentSessionId = System.currentTimeMillis().toString() }
                }
            }

            is ChatIntent.StopGeneration -> {
                val currentGenId = cognitiveTaskScheduler.state.value.activeGenerationId ?: ""
                intentThreadManager.suspendIntent(
                    generationId = currentGenId,
                    prompt = currentUserQuery,
                    partialResponse = _streamingState.value.visibleText
                )
                
                // SAVE PARTIAL MESSAGE BEFORE WIPING
                val currentStreaming = _streamingState.value
                val parsed = parseStreamContent(currentStreaming.rawContent)
                if (parsed.visibleText.isNotBlank() || parsed.thoughts.isNotEmpty()) {
                    val finalMsg = ChatMessage(
                        text = parsed.visibleText,
                        isUser = false,
                        thoughts = parsed.thoughts,
                        actions = currentStreaming.actions,
                        rawContent = currentStreaming.rawContent
                    )
                    allMessages.add(finalMsg)
                    pushMessages()

                    val snapshot = allMessages.toList()
                    viewModelScope.launch(Dispatchers.IO) {
                        historyManager?.saveSession(currentSessionId, snapshot)
                        withContext(Dispatchers.Main) { refreshSessions() }
                    }
                }
                
                _streamingState.value = StreamingState()
                _uiState.update { it.copy(isGenerating = false) }
                cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.StopGeneration(currentGenId))
            }

            is ChatIntent.ClearHistory -> {
                allMessages.clear(); pushMessages()
                currentSessionId = System.currentTimeMillis().toString()
                _uiState.update { it.copy(isGenerating = false) }
            }

            is ChatIntent.LoadModel -> {
                _uiState.update { it.copy(isLoadingModel = true, errorMessage = null, fallbackMessage = null, fallbackErrorDetails = null) }
                viewModelScope.launch {
                    try {
                        if (intent.isOrchestrator) {
                            val backendPref = settingsManager?.orchestratorHardwareBackend ?: "CPU"
                            Log.d("ChatViewModel", "Loading orchestrator model: ${intent.path} with backend=$backendPref")
                            val result = llmInferenceManager?.loadOrchestratorModel(intent.path, backendPref)
                            val actualBackend = result?.backendName ?: "Unknown"
                            Log.d("ChatViewModel", "Orchestrator loaded on $actualBackend (requested: $backendPref)")

                            val fallback = detectFallback(backendPref, actualBackend, result)
                            _uiState.update { it.copy(
                                isLoadingModel = false,
                                status = "Orchestrator Loaded ($actualBackend)",
                                fallbackMessage = fallback,
                                fallbackErrorDetails = result?.errorDetails
                            ) }
                        } else {
                            val backendPref = settingsManager?.mainHardwareBackend ?: "CPU"
                            Log.d("ChatViewModel", "Loading main model: ${intent.path} with backend=$backendPref")
                            val result = llmInferenceManager?.loadMainModel(intent.path, backendPref)
                            val actualBackend = result?.backendName ?: "Unknown"
                            Log.d("ChatViewModel", "Main model loaded on $actualBackend (requested: $backendPref)")

                            val fallback = detectFallback(backendPref, actualBackend, result)
                            _uiState.update { it.copy(
                                isLoadingModel = false,
                                status = "Model Loaded ($actualBackend)",
                                activeBackend = actualBackend,
                                fallbackMessage = fallback,
                                fallbackErrorDetails = result?.errorDetails
                            ) }
                        }
                    } catch (e: Throwable) {
                        Log.e("ChatViewModel", "Model loading failed", e)
                        val fullStackTrace = buildString {
                            appendLine(e::class.qualifiedName ?: e::class.simpleName)
                            appendLine(e.message ?: "No message")
                            appendLine()
                            e.stackTrace.take(15).forEach { frame ->
                                appendLine("  at $frame")
                            }
                            if (e.stackTrace.size > 15) appendLine("  ... (${e.stackTrace.size - 15} more frames)")
                        }
                        _uiState.update { it.copy(
                            isLoadingModel = false, 
                            errorMessage = "Failed to load model: ${e.message}",
                            fatalErrorDetails = fullStackTrace
                        ) }
                    }
                }
            }

            is ChatIntent.UnloadModel -> {
                viewModelScope.launch {
                    try {
                        if (intent.isOrchestrator) {
                            llmInferenceManager?.unloadOrchestratorModel()
                            _uiState.update { it.copy(status = "Orchestrator Unloaded") }
                        } else {
                            llmInferenceManager?.unloadMainModel()
                            _uiState.update { it.copy(status = "Model Unloaded", activeBackend = null) }
                        }
                    } catch (e: Throwable) {
                        Log.e("ChatViewModel", "Error unloading model: ${e.message}")
                    }
                }
            }

            is ChatIntent.ModelLoaded -> _uiState.update { it.copy(activeBackend = intent.backend) }
            is ChatIntent.SetError -> { _uiState.update { it.copy(errorMessage = intent.message, isGenerating = false, isLoadingModel = false) } }
            is ChatIntent.ActivateVoiceMode -> {
                _uiState.update { it.copy(isVoiceModeActive = true, voiceState = VoiceState.LISTENING) }
                conversationEngine?.start()
            }
            is ChatIntent.DeactivateVoiceMode -> {
                _uiState.update { it.copy(isVoiceModeActive = false, voiceState = VoiceState.IDLE, partialTranscript = "") }
                conversationEngine?.stop()
            }
            is ChatIntent.SetVoiceState -> {
                _uiState.update { it.copy(voiceState = intent.state) }
                if (intent.state == VoiceState.LISTENING) {
                    conversationEngine?.interrupt()
                    if (_uiState.value.isGenerating) {
                        processIntent(ChatIntent.StopGeneration)
                    }
                }
            }
            is ChatIntent.SetPartialTranscript -> _uiState.update { it.copy(partialTranscript = intent.text) }
            is ChatIntent.StartDictation -> {
                _uiState.update { it.copy(isDictating = true, partialTranscript = "", finalDictatedText = null) }
                // Dictation mode uses Android SpeechRecognizer directly via ConversationEngine listen path
                conversationEngine?.start()
            }
            is ChatIntent.StopDictation -> {
                _uiState.update { it.copy(isDictating = false) }
                conversationEngine?.stop()
            }
            is ChatIntent.ClearDictatedText -> {
                _uiState.update { it.copy(finalDictatedText = null) }
            }
        }
    }

    fun clearModelError() {
        _uiState.update { it.copy(errorMessage = null, fatalErrorDetails = null) }
    }

    fun clearFallbackWarning() {
        _uiState.update { it.copy(fallbackMessage = null, fallbackErrorDetails = null) }
    }

    /**
     * Detects if a backend fallback occurred by comparing what the user requested
     * vs what was actually loaded. Returns a user-friendly message or null if no fallback.
     * Shows the real caught error — no hardcoded device-specific guesses.
     */
    private fun detectFallback(
        requestedBackend: String,
        actualBackend: String,
        loadResult: com.example.llmapp.core.inference.LlmInferenceManager.LoadResult?
    ): String? {
        if (requestedBackend == actualBackend) return null

        val caughtError = loadResult?.fallbackError

        // For "Auto", the resolved target is GPU — if we ended up on CPU, that's a fallback
        val displayRequested = if (requestedBackend == "Auto") "Auto (GPU)" else requestedBackend

        return buildString {
            append("Requested: $displayRequested → Loaded on: $actualBackend\n\n")
            append("The $displayRequested backend failed to initialize. ")
            append("The model was loaded on $actualBackend instead.")
            if (caughtError != null) {
                append("\n\nError: ${caughtError.message}")
            }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────────
    private fun handleSendMessage(text: String) {
        if (text.isBlank()) return
        
        // Stop any ongoing generation (e.g. from a barge-in or manual tap) before starting the new one
        if (_uiState.value.isGenerating) {
            processIntent(ChatIntent.StopGeneration)
        }
        
        socioCognitiveRegulator.recordUserInteraction()
        contextEntropyMonitor.analyzeEntropy(text)
        
        currentUserQuery = text
        searchRetryCount = 0 // Reset retry counter for each new user message
        searchChainDepth = 0 // Reset chain depth limit

        val userMessage = ChatMessage(text = text, isUser = true)
        allMessages.add(userMessage)
        if (allMessages.size > MAX_CONTEXT) allMessages.removeAt(0)
        pushMessages()

        _streamingState.value = StreamingState()
        _uiState.update { it.copy(isGenerating = true, errorMessage = null) }

        val genId = java.util.UUID.randomUUID().toString()
        
        // ── Phase 1: System-Driven Intent Classification ──
        val intent = com.example.llmapp.core.search.SearchIntentClassifier.classify(text, allMessages)
        
        viewModelScope.launch(Dispatchers.Default) {
            val pacingDelay = socioCognitiveRegulator.calculatePacingDelayMs()
            if (pacingDelay > 0) {
                kotlinx.coroutines.delay(pacingDelay)
            }
            
            if (intent.needed && intent.query != null) {
                // We need search! Let's update UI and orchestrate the search directly
                withContext(Dispatchers.Main) {
                    val actions = _streamingState.value.actions.toMutableList()
                    actions.add(com.example.llmapp.AgentAction(
                        toolName = "Web Search",
                        query = intent.query,
                        uiSources = "Searching Web (${intent.type.name}): ${intent.query}"
                    ))
                    _streamingState.value = _streamingState.value.copy(actions = actions)
                }
                
                val orchestrator = searchOrchestrator
                if (orchestrator != null) {
                    val result = try {
                        orchestrator.resolveSearch(intent.query, forceSearch = true)
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Orchestrator threw unexpectedly: ${e.message}")
                        com.example.llmapp.core.search.orchestration.OrchestratorResult.Failed("Internal error")
                    }
                    
                    withContext(Dispatchers.Main) {
                        when (result) {
                            is com.example.llmapp.core.search.orchestration.OrchestratorResult.Success -> {
                                storeObservation(result.formattedContext, result.statusMessage, genId, intent.type)
                            }
                            is com.example.llmapp.core.search.orchestration.OrchestratorResult.NoResults -> {
                                val noResultNote = "Web search returned no results for \"${result.query}\". " +
                                    "Answer using your internal knowledge and clearly state if you are uncertain."
                                storeObservation(noResultNote, "No results found", genId, intent.type)
                            }
                            is com.example.llmapp.core.search.orchestration.OrchestratorResult.Failed -> {
                                fallbackGenerationWithoutSearch(genId, reason = result.error)
                            }
                            is com.example.llmapp.core.search.orchestration.OrchestratorResult.Skipped -> {
                                fallbackGenerationWithoutSearch(genId, reason = null)
                            }
                        }
                    }
                } else {
                    // Fallback duckduckgo skill
                    val skill = com.example.llmapp.core.skills.WebSearchSkill()
                    val (uiText, llmText) = skill.search(intent.query)
                    withContext(Dispatchers.Main) { 
                        storeObservation(llmText, uiText, genId, intent.type) 
                    }
                }
            } else {
                // Direct Conversational response
                val mgr = settingsManager
                val customPrompt = mgr?.systemPrompt ?: ""
                val regulatoryPrompt = socioCognitiveRegulator.generateRegulatoryPrompt()
                val antiDependencyPrompt = identityAnchorManager?.checkAntiDependencyProtocol(text) ?: ""
                
                val suspended = intentThreadManager.popNextIntent()
                val suspendedPrompt = if (suspended != null) {
                    "\n[SOCIO-COGNITIVE: You were recently interrupted while answering '${suspended.originalPrompt}'. Your last words were '${suspended.partialResponse}'. If relevant to the current flow, you may briefly conclude that thought.]\n"
                } else ""

                // Assemble memory observations
                val sbMemories = StringBuilder()
                if (activeDegradationLevel < com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.REDUCED_RETRIEVAL) {
                    val memories = runCatching {
                        withContext(Dispatchers.IO) {
                            hybridRetriever?.retrieveRelevance(text) ?: emptyList()
                        }
                    }.getOrElse { emptyList() }
                    if (memories.isNotEmpty()) {
                        sbMemories.append("\nRelevant context from memory:\n")
                        memories.forEach { sbMemories.append("- ${it.content}\n") }
                    }
                }
                
                val systemPromptOverride = customPrompt + "\n" + regulatoryPrompt + "\n" + antiDependencyPrompt + "\n" + suspendedPrompt + "\n" + sbMemories.toString()
                
                val ctx = com.example.llmapp.core.prompts.models.PromptContext(
                    systemPromptOverride = systemPromptOverride,
                    userName = mgr?.userName ?: "",
                    userDob = mgr?.userDob ?: "",
                    userLocation = mgr?.userLocation ?: "",
                    userBio = mgr?.userBio ?: "",
                    activeDegradationLevel = activeDegradationLevel,
                    contextLimit = mgr?.contextLimit ?: 10,
                    perMessageCap = perMessageCharCap()
                )
                
                val prompt = com.example.llmapp.core.prompts.PromptPipeline.buildNormalPrompt(text, allMessages, ctx)
                cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.GenerationRequested(text, prompt, genId))
            }
        }
    }

    private fun perMessageCharCap(): Int {
        val maxTokens = settingsManager?.maxTokens ?: 1024
        val limit = (settingsManager?.contextLimit ?: 10).coerceAtLeast(1)
        return ((maxTokens * 4 - 1680) / limit).coerceIn(400, 8000)
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────
    private fun jsonToStringList(json: String): List<String> = try { val a = JSONArray(json); List(a.length()) { a.getString(it) } } catch (_: Exception) { emptyList() }
    private fun jsonToActionsList(json: String): List<AgentAction> = try {
        val a = JSONArray(json); List(a.length()) { i -> val o = a.getJSONObject(i); AgentAction(o.getString("toolName"), o.getString("query"), o.optString("result", null), o.optString("uiSources", null)) }
    } catch (_: Exception) { emptyList() }

    override fun onCleared() { super.onCleared(); llmInferenceManager?.close(); embeddingManager?.close() }
}
