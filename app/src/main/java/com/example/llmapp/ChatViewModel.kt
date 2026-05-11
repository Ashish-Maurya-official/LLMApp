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
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import java.io.File

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
            
            // Initialize Voice Interaction Manager
            voiceInteractionManager = com.example.llmapp.core.audio.VoiceInteractionManager(hMgr.context, viewModelScope)
            viewModelScope.launch {
                voiceInteractionManager?.events?.collect { handleVoiceEvent(it) }
            }

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
        
    var voiceInteractionManager: com.example.llmapp.core.audio.VoiceInteractionManager? = null
        private set

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

    var llmInferenceManager: LlmInferenceManager? = null
        set(value) {
            field = value
            cognitiveTaskScheduler.llmInferenceManager = value
            
            // Wire EvaluationRunner
            historyManager?.let { hMgr ->
                hybridRetriever?.let { retriever ->
                    value?.let { inferenceManager ->
                        evaluationRunner = com.example.llmapp.core.evaluation.EvaluationRunner(hMgr.database, retriever, inferenceManager)
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
                        voiceInteractionManager?.onNewLlmToken(newTts, event.isDone)
                    }
                } else if (event.isDone && parsed.visibleText.isNotEmpty()) {
                    onNewToken?.invoke("", true)
                    voiceInteractionManager?.onNewLlmToken("", true)
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

                _streamingState.value = StreamingState()
                _uiState.update { it.copy(isGenerating = false) }
            }

            is com.example.llmapp.core.runtime.CognitiveEvent.ToolEvent.SearchRequested -> {
                _streamingState.value = StreamingState(actions = listOf(AgentAction("WebSearch", event.query)))
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

    private fun handleVoiceEvent(event: com.example.llmapp.core.audio.VoiceEvent) {
        when (event) {
            is com.example.llmapp.core.audio.VoiceEvent.PartialTranscript -> {
                _uiState.update { it.copy(partialTranscript = event.text) }
            }
            is com.example.llmapp.core.audio.VoiceEvent.FinalTranscript -> {
                _uiState.update { it.copy(partialTranscript = "") }
                handleSendMessage(event.text)
            }
            is com.example.llmapp.core.audio.VoiceEvent.UserStartedSpeaking -> {
                // Barge-in: User interrupted the AI.
                if (_uiState.value.isGenerating) {
                    processIntent(ChatIntent.StopGeneration)
                }
            }
            is com.example.llmapp.core.audio.VoiceEvent.UserStoppedSpeaking -> {
                // Audio processing
            }
            is com.example.llmapp.core.audio.VoiceEvent.AIStartedSpeaking -> {
                _uiState.update { it.copy(voiceState = VoiceState.SPEAKING) }
            }
            is com.example.llmapp.core.audio.VoiceEvent.AIFinishedSpeaking -> {
                if (_uiState.value.isVoiceModeActive) {
                    _uiState.update { it.copy(voiceState = VoiceState.LISTENING) }
                    voiceInteractionManager?.startListening()
                } else {
                    _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
                }
            }
            is com.example.llmapp.core.audio.VoiceEvent.Error -> {
                Log.e("VoiceInteraction", "Voice Error: \${event.message}")
            }
        }
    }

    // ── Web search ────────────────────────────────────────────────────────────
    private fun performWebSearch(query: String, genId: String) {
        Log.d("ChatViewModel", "performWebSearch: \"$query\"")
        viewModelScope.launch(Dispatchers.IO) {
            val skill = com.example.llmapp.core.skills.WebSearchSkill()
            val (uiText, llmText) = skill.search(query)
            withContext(Dispatchers.Main) { storeObservation(llmText, uiText, genId) }
        }
    }

    private fun storeObservation(llmText: String, uiText: String?, genId: String) {
        // Update the action chip with clickable sources
        val actions = _streamingState.value.actions.toMutableList()
        if (actions.isNotEmpty() && uiText != null) {
            actions[actions.lastIndex] = actions.last().copy(uiSources = uiText)
        }
        _streamingState.value = _streamingState.value.copy(actions = actions)

        // Let the scheduler know search is complete
        cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.ToolEvent.SearchCompleted(llmText, genId))
        
        _uiState.update { it.copy(isGenerating = true) }
        val promptWithContext = buildPromptWithContext(currentUserQuery, llmText)
        cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.GenerationRequested(promptWithContext, genId))
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
                _uiState.update { it.copy(isLoadingModel = true, errorMessage = null) }
                viewModelScope.launch {
                    try { llmInferenceManager?.loadModel(intent.path); _uiState.update { it.copy(isLoadingModel = false, status = "Model Loaded") } }
                    catch (e: Exception) { _uiState.update { it.copy(isLoadingModel = false, errorMessage = e.message) } }
                }
            }

            is ChatIntent.ModelLoaded -> _uiState.update { it.copy(activeBackend = intent.backend) }
            is ChatIntent.SetError -> { _uiState.update { it.copy(errorMessage = intent.message, isGenerating = false, isLoadingModel = false) } }
            is ChatIntent.ActivateVoiceMode -> {
                _uiState.update { it.copy(isVoiceModeActive = true, voiceState = VoiceState.LISTENING) }
                voiceInteractionManager?.startListening()
            }
            is ChatIntent.DeactivateVoiceMode -> {
                _uiState.update { it.copy(isVoiceModeActive = false, voiceState = VoiceState.IDLE, partialTranscript = "") }
                voiceInteractionManager?.stopListening()
                voiceInteractionManager?.stopSpeaking()
            }
            is ChatIntent.SetVoiceState -> _uiState.update { it.copy(voiceState = intent.state) }
            is ChatIntent.SetPartialTranscript -> _uiState.update { it.copy(partialTranscript = intent.text) }
        }
    }

    // ── Send message ──────────────────────────────────────────────────────────
    private fun handleSendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isGenerating) return
        
        socioCognitiveRegulator.recordUserInteraction()
        contextEntropyMonitor.analyzeEntropy(text)
        
        currentUserQuery = text

        val userMessage = ChatMessage(text = text, isUser = true)
        allMessages.add(userMessage)
        if (allMessages.size > MAX_CONTEXT) allMessages.removeAt(0)
        pushMessages()

        _streamingState.value = StreamingState()
        _uiState.update { it.copy(isGenerating = true, errorMessage = null) }

        val genId = java.util.UUID.randomUUID().toString()
        val prompt = buildPromptFromHistory(text)
        
        viewModelScope.launch {
            val pacingDelay = socioCognitiveRegulator.calculatePacingDelayMs()
            if (pacingDelay > 0) {
                kotlinx.coroutines.delay(pacingDelay)
            }
            cognitiveTaskScheduler.emit(com.example.llmapp.core.runtime.CognitiveEvent.RuntimeEvent.GenerationRequested(prompt, genId))
        }
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    private fun buildPromptFromHistory(pendingUserText: String): String {
        val systemPrompt = buildSystemPrompt()
        val regulatoryPrompt = socioCognitiveRegulator.generateRegulatoryPrompt()
        val antiDependencyPrompt = identityAnchorManager?.checkAntiDependencyProtocol(pendingUserText) ?: ""
        
        val suspended = intentThreadManager.popNextIntent()
        val suspendedPrompt = if (suspended != null) {
            "\n[SOCIO-COGNITIVE: You were recently interrupted while answering '\${suspended.originalPrompt}'. Your last words were '\${suspended.partialResponse}'. If relevant to the current flow, you may briefly conclude that thought.]\n"
        } else ""
        
        val sb = StringBuilder()

        sb.append("<start_of_turn>user\n")
        sb.append(systemPrompt)
        sb.append(regulatoryPrompt)
        sb.append(antiDependencyPrompt)
        sb.append(suspendedPrompt)
        sb.append("\n\n## WEB SEARCH TOOL\n")
        sb.append("You have access to a real-time web search tool.\n")
        sb.append("When you need current information (weather, news, live scores, prices, recent events), ")
        sb.append("output EXACTLY this on its own line and NOTHING else:\n")
        sb.append("[SEARCH_NEEDED: <your search query here>]\n\n")
        sb.append("The query inside must be a literal, ready-to-use Google search (no placeholders like [city]).\n")
        sb.append("If the user's profile has location/name info, use it directly in the query.\n")
        sb.append("Do NOT say \"I don't have access\" or \"I cannot browse\" — just output the sentinel.\n")
        sb.append("If you CAN answer from your own knowledge, answer normally — do NOT use the sentinel.\n")
        sb.append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\nUnderstood. I will output [SEARCH_NEEDED: <query>] when I need real-time data.<end_of_turn>\n")

        // Few-shot examples — show both the format AND the extracted query
        sb.append("<start_of_turn>user\nWhat is the weather in Delhi right now?<end_of_turn>\n")
        sb.append("<start_of_turn>model\n[SEARCH_NEEDED: Delhi weather today]<end_of_turn>\n")
        sb.append("<start_of_turn>user\nWhat is the capital of France?<end_of_turn>\n")
        sb.append("<start_of_turn>model\nThe capital of France is Paris.<end_of_turn>\n")
        sb.append("<start_of_turn>user\nLatest IPL 2025 scores?<end_of_turn>\n")
        sb.append("<start_of_turn>model\n[SEARCH_NEEDED: IPL 2025 latest match scores today]<end_of_turn>\n")
        sb.append("<start_of_turn>user\nBitcoin price right now?<end_of_turn>\n")
        sb.append("<start_of_turn>model\n[SEARCH_NEEDED: Bitcoin price USD live]<end_of_turn>\n")
        sb.append("<start_of_turn>user\nWho is the Prime Minister of India?<end_of_turn>\n")
        sb.append("<start_of_turn>model\nThe Prime Minister of India is Narendra Modi.<end_of_turn>\n")

        // Inject FTS memories if available
        if (activeDegradationLevel < com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.REDUCED_RETRIEVAL) {
            val memories = runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    hybridRetriever?.retrieveRelevance(pendingUserText) ?: emptyList()
                }
            }.getOrElse { emptyList() }
            if (memories.isNotEmpty()) {
                sb.append("<start_of_turn>user\nRelevant context from memory:\n")
                memories.forEach { sb.append("- ${it.content}\n") }
                sb.append("<end_of_turn>\n<start_of_turn>model\nNoted.<end_of_turn>\n")
            }
        }

        val limit = if (activeDegradationLevel >= com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.SUMMARIZE_CONTEXT) 3 else (settingsManager?.contextLimit ?: 10)
        val msgCap = perMessageCharCap()
        allMessages.dropLast(1).takeLast(limit).forEach { msg ->
            val content = msg.text.take(msgCap); if (content.isBlank()) return@forEach
            if (msg.isUser) sb.append("<start_of_turn>user\n$content<end_of_turn>\n")
            else sb.append("<start_of_turn>model\n$content<end_of_turn>\n")
        }

        sb.append("<start_of_turn>user\n${pendingUserText.trim()}<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildPromptWithContext(userText: String, searchContext: String): String {
        val sb = StringBuilder()
        sb.append("<start_of_turn>user\n")
        sb.append(buildSystemPrompt())
        sb.append("<end_of_turn>\n<start_of_turn>model\nSure, I am ready to help!<end_of_turn>\n")

        val limit = if (activeDegradationLevel >= com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.SUMMARIZE_CONTEXT) 3 else (settingsManager?.contextLimit ?: 10)
        val msgCap = perMessageCharCap()
        allMessages.dropLast(1).takeLast(limit).forEach { msg ->
            val content = msg.text.take(msgCap); if (content.isBlank()) return@forEach
            if (msg.isUser) sb.append("<start_of_turn>user\n$content<end_of_turn>\n")
            else sb.append("<start_of_turn>model\n$content<end_of_turn>\n")
        }

        val searchCap = (settingsManager?.maxTokens ?: 1024) * 3
        sb.append("<start_of_turn>user\n")
        sb.append("The following web search results were retrieved to help answer the question. The user CANNOT see these results — only your reply is shown.\n\n")
        sb.append("=== SEARCH RESULTS START ===\n${searchContext.take(searchCap)}\n=== SEARCH RESULTS END ===\n\n")
        sb.append("USER'S QUESTION: $userText\n\n")
        sb.append("Rules: (1) Extract only info that answers the question. (2) Do NOT mention 'search results'. (3) Include specific facts (numbers, dates). (4) Do NOT output [SEARCH_NEEDED: ...]. (5) Format with Markdown.\n")
        sb.append("<end_of_turn>\n<start_of_turn>model\n")
        return sb.toString()
    }


    private fun buildSystemPrompt(): String {
        val mgr = settingsManager ?: return "You are a helpful AI assistant."
        val sb = StringBuilder("You are a helpful AI assistant.")
        val custom = mgr.systemPrompt; if (custom.isNotBlank()) sb.append("\n$custom")
        val name = mgr.userName; val dob = mgr.userDob; val loc = mgr.userLocation; val bio = mgr.userBio
        if (listOf(name, dob, loc, bio).any { it.isNotBlank() }) {
            sb.append("\n\nUSER INFORMATION:")
            if (name.isNotBlank()) sb.append("\n- Name: $name")
            if (dob.isNotBlank()) sb.append("\n- DOB: $dob")
            if (loc.isNotBlank()) sb.append("\n- Location: $loc")
            if (bio.isNotBlank()) sb.append("\n- Bio: $bio")
        }
        val dt = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
        sb.append("\n\nCurrent date/time: $dt")
        sb.append("\n\nFormatting: use Markdown — **bold**, bullet lists, ### headers, tables, `code`. Keep responses concise for mobile.")
        return sb.toString()
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
