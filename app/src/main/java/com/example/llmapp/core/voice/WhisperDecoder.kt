package com.example.llmapp.core.voice

/**
 * Decodes Whisper token IDs into human-readable text.
 * Note: A full BPE implementation requires loading a large vocab file.
 * This is a simplified version using the standard byte-level fallback.
 */
class WhisperDecoder {

    /**
     * Decodes a list of token IDs.
     */
    fun decode(tokens: IntArray): String {
        val sb = StringBuilder()
        
        for (token in tokens) {
            // Whisper special tokens are usually > 50256
            if (token > 50256) continue
            
            // Simplified: Many Whisper models use standard GPT-2 / Byte-level BPE.
            // Tokens 0-255 are often just the raw bytes.
            if (token in 0..255) {
                sb.append(token.toChar())
            } else {
                // Fallback for common words if we don't have the full vocab
                val word = commonVocab[token]
                if (word != null) {
                    sb.append(word)
                } else {
                    // sb.append(" [token_$token] ") // Debug
                }
            }
        }
        
        return sb.toString().trim()
            .replace("Ġ", " ") // GPT-2 style space prefix
            .replace("Ċ", "\n")
    }

    private val commonVocab = mapOf(
        // Very common words for testing
        50256 to " ",
        13 to ".",
        11 to ",",
        30 to "!",
        318 to " is",
        262 to " the",
        284 to " to",
        257 to " a",
        314 to " I",
        460 to " you",
        422 to " what",
        259 to " and",
        267 to " of",
        273 to " in",
        293 to " it",
        338 to "'s",
        356 to " are",
        511 to " was",
        581 to " with",
        588 to " for",
        655 to " on",
        674 to " have",
        703 to " be",
        764 to " that",
        881 to " this",
        1544 to " Hello",
        2354 to " how",
        301 to " do",
        307 to " me"
    )
}
