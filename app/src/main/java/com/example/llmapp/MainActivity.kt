package com.example.llmapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import android.content.IntentFilter
import android.content.Context
import androidx.navigation.compose.rememberNavController
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.models.ModelDownloader
import com.example.llmapp.core.settings.SettingsManager
import com.example.llmapp.core.history.ChatHistoryManager
import com.example.llmapp.ui.theme.LLMAppTheme
import com.example.llmapp.ui.chat.ChatScreen
import com.example.llmapp.ui.models.ModelScreen
import com.example.llmapp.ui.settings.SettingsScreen
import com.example.llmapp.ui.history.HistoryScreen
import com.example.llmapp.ui.state.ChatIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var llmInferenceManager: LlmInferenceManager
    private lateinit var modelDownloader: ModelDownloader
    private lateinit var settingsManager: SettingsManager
    private lateinit var historyManager: ChatHistoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enable 120fps / High Refresh Rate
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val modes = window.windowManager.defaultDisplay.supportedModes
            val maxMode = modes.maxByOrNull { it.refreshRate }
            if (maxMode != null) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = maxMode.modeId
                }
            }
        }

        llmInferenceManager = LlmInferenceManager(this)
        modelDownloader = ModelDownloader(this)
        settingsManager = SettingsManager(this)
        historyManager = ChatHistoryManager(this)
        
        viewModel.llmInferenceManager = llmInferenceManager
        viewModel.settingsManager = settingsManager
        viewModel.historyManager = historyManager

        val chaosTestRunner = com.example.llmapp.core.runtime.ChaosTestRunner(viewModel.cognitiveTaskScheduler)
        val filter = IntentFilter("com.example.llmapp.CHAOS_TEST")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chaosTestRunner, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(chaosTestRunner, filter)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            powerManager.addThermalStatusListener { status ->
                viewModel.cognitiveTaskScheduler.notifyThermalStatusChanged(status)
            }
        }

        // Schedule the Cognitive Sleep Cycle (WorkManager)
        com.example.llmapp.core.sleep.SleepCycleScheduler.scheduleSleepCycle(this)

        setContent {
            val themePref by viewModel.themePreference.collectAsState()
            val isDark = when (themePref) {
                "Dark" -> true
                "Light" -> false
                else -> isSystemInDarkTheme()
            }

            LLMAppTheme(darkTheme = isDark) {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                
                val sessions by viewModel.sessionList.collectAsState()

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Text(
                                "Recent Chats", 
                                modifier = Modifier.padding(16.dp), 
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            val recentSessions = sessions.take(5)
                            recentSessions.forEach { session ->
                                NavigationDrawerItem(
                                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
                                    label = { 
                                        Text(
                                            session.title, 
                                            maxLines = 1 
                                        ) 
                                    },
                                    selected = false,
                                    onClick = {
                                        scope.launch { drawerState.close() }
                                        viewModel.processIntent(ChatIntent.RestoreSession(session.id))
                                        navController.navigate("chat") {
                                            popUpTo("chat") { inclusive = true }
                                        }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                            
                            if (sessions.size > 5) {
                                NavigationDrawerItem(
                                    icon = { Icon(Icons.Default.History, contentDescription = "View all") },
                                    label = { Text("View all history") },
                                    selected = currentRoute == "history",
                                    onClick = {
                                        scope.launch { drawerState.close() }
                                        navController.navigate("history") {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                            
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                                label = { Text("Profile") },
                                selected = currentRoute == "profile",
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("profile") {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Storage, contentDescription = "Models") },
                                label = { Text("Models") },
                                selected = currentRoute == "models",
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("models") {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                            
                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                label = { Text("Settings") },
                                selected = currentRoute == "settings",
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    navController.navigate("settings") {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            )
                        }
                    }
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "models"
                    ) {
                        composable("chat") {
                            val uiState by viewModel.uiState.collectAsState()
                            val sessionMessages by viewModel.sessionMessages.collectAsState()
                            val streamingState = viewModel.streamingState.collectAsState()
                            ChatScreen(
                                uiState = uiState,
                                sessionMessages = sessionMessages,
                                streamingState = streamingState,
                                onIntent = { intent -> viewModel.processIntent(intent) },
                                openDrawer = { scope.launch { drawerState.open() } },
                                onRegisterTokenCallback = { callback ->
                                    viewModel.onNewToken = callback
                                },
                                settingsManager = settingsManager
                            )
                        }
                            composable("models") {
                                com.example.llmapp.ui.models.ModelScreen(
                                    modelDownloader = modelDownloader,
                                    onModelSelected = { path ->
                                        initModel(path)
                                        navController.navigate("chat") {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    openDrawer = { scope.launch { drawerState.open() } },
                                    onOpenEvaluation = { navController.navigate("evaluation") }
                                )
                            }
                            composable("evaluation") {
                                viewModel.evaluationRunner?.let { runner ->
                                    com.example.llmapp.ui.evaluation.EvaluationScreen(
                                        runner = runner,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                            composable("history") {
                                HistoryScreen(
                                    sessions = sessions,
                                    onDeleteSession = { session -> viewModel.processIntent(ChatIntent.DeleteSession(session.id)) },
                                    onSessionSelected = { session ->
                                        viewModel.processIntent(ChatIntent.RestoreSession(session.id))
                                        navController.navigate("chat") {
                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    openDrawer = { scope.launch { drawerState.open() } }
                                )
                            }
                            composable("settings") {
                                val currentTheme by viewModel.themePreference.collectAsState()
                                SettingsScreen(
                                    settingsManager = settingsManager,
                                    currentTheme = currentTheme,
                                    onThemeChanged = { newTheme -> viewModel.updateTheme(newTheme) },
                                    openDrawer = { scope.launch { drawerState.open() } }
                                )
                            }
                            composable("profile") {
                                com.example.llmapp.ui.profile.ProfileScreen(
                                    settingsManager = settingsManager,
                                    openDrawer = { scope.launch { drawerState.open() } }
                                )
                            }
                        }
                    }
                }
        }
    }
    private fun initModel(path: String) {
        viewModel.processIntent(ChatIntent.LoadModel(path))
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loadedBackend = llmInferenceManager.loadModel(
                    modelPath = path,
                    hardwareBackend = settingsManager.hardwareBackend,
                    maxTokens = settingsManager.maxTokens,
                    temperature = settingsManager.temperature,
                    topK = settingsManager.topK
                )
                withContext(Dispatchers.Main) {
                    viewModel.processIntent(ChatIntent.ModelLoaded(loadedBackend))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    viewModel.processIntent(ChatIntent.SetError("Failed to load model: ${e.message}"))
                }
            }
        }
    }
}
