package com.example.llmapp.core.voice

/**
 * Decodes Whisper token IDs into human-readable text.
 * Note: A full BPE implementation requires loading a large vocab file.
 * This is a simplified version using the standard byte-level fallback.
 */
class WhisperDecoder(context: android.content.Context) {
    private val vocab = mutableMapOf<Int, String>()

    init {
        try {
            context.assets.open("whisper_vocab.txt").bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    vocab[index] = line
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Decodes a list of token IDs.
     */
    fun decode(tokens: IntArray): String {
        val byteList = mutableListOf<Byte>()
        
        for (token in tokens) {
            // Whisper special tokens
            if (token > 50256) continue
            
            val word = vocab[token]
            if (word != null) {
                // Whisper vocab often uses "Ġ" for space or "Ċ" for newline
                val cleanWord = word.replace("Ġ", " ").replace("Ċ", "\n")
                byteList.addAll(cleanWord.toByteArray(Charsets.UTF_8).toList())
            } else if (token in 0..255) {
                byteList.add(token.toByte())
            }
        }
        
        return String(byteList.toByteArray(), Charsets.UTF_8).trim()
    }

    // (Remove commonVocab map)
}
