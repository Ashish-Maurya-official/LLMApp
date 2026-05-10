package com.example.llmapp.core.retrieval

import android.content.Context
import android.util.Log
import java.io.File

/**
 * A lightweight WordPiece tokenizer for all-MiniLM-L6-v2.
 * Handles text-to-ID conversion for the ONNX embedding model.
 */
class Tokenizer(private val context: Context) {

    private val vocab = mutableMapOf<String, Int>()
    private var isInitialized = false

    /**
     * Loads the vocabulary file from internal storage or assets.
     */
    fun initialize(vocabPath: String) {
        try {
            val file = File(vocabPath)
            if (!file.exists()) {
                Log.e("Tokenizer", "Vocab file not found at $vocabPath")
                return
            }
            file.forEachLine { line ->
                val parts = line.split(" ")
                if (parts.size >= 1) {
                    val token = parts[0]
                    // Basic vocab files are just one token per line
                    vocab[token] = vocab.size
                }
            }
            isInitialized = true
            Log.d("Tokenizer", "Vocabulary loaded: ${vocab.size} tokens")
        } catch (e: Exception) {
            Log.e("Tokenizer", "Failed to load vocabulary", e)
        }
    }

    /**
     * Simple WordPiece tokenization logic.
     */
    fun tokenize(text: String): LongArray {
        if (!isInitialized) return longArrayOf()

        val tokens = mutableListOf<Long>()
        // Simplified: Split by whitespace and punctuation
        val words = text.lowercase().split(Regex("[\\s\\p{Punct}]+"))
        
        // Add [CLS] token (usually index 101 in BERT/MiniLM)
        tokens.add(vocab["[CLS]"]?.toLong() ?: 101L)

        for (word in words) {
            if (word.isEmpty()) continue
            
            var remaining = word
            while (remaining.isNotEmpty()) {
                var found = false
                for (i in remaining.length downTo 1) {
                    val sub = remaining.substring(0, i)
                    val key = if (remaining.length == word.length) sub else "##$sub"
                    val id = vocab[key]
                    if (id != null) {
                        tokens.add(id.toLong())
                        remaining = remaining.substring(i)
                        found = true
                        break
                    }
                }
                if (!found) {
                    tokens.add(vocab["[UNK]"]?.toLong() ?: 100L)
                    break
                }
            }
        }

        // Add [SEP] token (usually index 102)
        tokens.add(vocab["[SEP]"]?.toLong() ?: 102L)
        
        return tokens.toLongArray()
    }
    
    fun getVocabSize(): Int = vocab.size
}
