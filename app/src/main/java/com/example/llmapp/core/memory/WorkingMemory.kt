package com.example.llmapp.core.memory

/**
 * Fast, short-term memory for the current conversation session.
 */
class WorkingMemory {
    private val buffer = mutableListOf<String>()

    fun addTurn(turn: String) {
        buffer.add(turn)
    }

    fun getContext(): List<String> = buffer.toList()

    fun clear() {
        buffer.clear()
    }
}
