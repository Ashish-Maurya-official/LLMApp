package com.example.llmapp.core.voice.pipeline

/**
 * Defines the conversational interaction mode.
 */
enum class DuplexMode {
    HALF_DUPLEX, // One speaks at a time (walkie-talkie style, but fast)
    SEMI_DUPLEX, // AI can generate "mm-hmm" while user speaks
    FULL_DUPLEX  // Both can speak simultaneously (advanced)
}
