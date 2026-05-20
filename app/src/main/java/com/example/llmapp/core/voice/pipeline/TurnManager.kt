package com.example.llmapp.core.voice.pipeline

/**
 * Manages who is currently speaking (User vs AI) and handles turn-taking logic.
 */
class TurnManager {
    private var isUserTurn = true

    fun onUserStartSpeaking() {
        isUserTurn = true
    }

    fun onAiStartSpeaking() {
        isUserTurn = false
    }

    fun isUserTurn(): Boolean = isUserTurn
}
