package com.example.llmapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.llmapp.core.inference.LlmInferenceManager
import com.example.llmapp.core.models.ModelDownloader
import com.example.llmapp.ui.chat.ChatScreen
import com.example.llmapp.ui.models.ModelScreen
import com.example.llmapp.ui.state.ChatIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var llmInferenceManager: LlmInferenceManager
    private lateinit var modelDownloader: ModelDownloader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        llmInferenceManager = LlmInferenceManager(this)
        modelDownloader = ModelDownloader(this)
        viewModel.llmInferenceManager = llmInferenceManager

        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Chat, contentDescription = "Chat") },
                                label = { Text("Chat") },
                                selected = currentRoute == "chat",
                                onClick = {
                                    navController.navigate("chat") {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Storage, contentDescription = "Models") },
                                label = { Text("Models") },
                                selected = currentRoute == "models",
                                onClick = {
                                    navController.navigate("models") {
                                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "models",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("chat") {
                            val uiState by viewModel.uiState.collectAsState()
                            ChatScreen(
                                uiState = uiState,
                                onIntent = { intent -> viewModel.processIntent(intent) }
                            )
                        }
                        composable("models") {
                            ModelScreen(
                                modelDownloader = modelDownloader,
                                onModelSelected = { path ->
                                    initModel(path)
                                    navController.navigate("chat")
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun initModel(path: String) {
        viewModel.processIntent(ChatIntent.LoadModel(path))
        MainScope().launch(Dispatchers.IO) {
            try {
                llmInferenceManager.loadModel(modelPath = path)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    viewModel.processIntent(ChatIntent.SetError("Failed to load model: ${e.message}"))
                }
            }
        }
    }
}
