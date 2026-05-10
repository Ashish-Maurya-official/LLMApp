package com.example.llmapp.core.retrieval

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import java.util.Collections
import kotlin.math.sqrt

/**
 * Handles generating text embeddings using an ONNX model (e.g., all-MiniLM-L6-v2).
 * These embeddings are used for semantic search and relevance ranking.
 */
class EmbeddingManager(private val context: Context) {

    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null
    private val tokenizer = Tokenizer(context)
    
    // Dimension for all-MiniLM-L6-v2 is 384
    private val embeddingDimension = 384

    /**
     * Initializes the ONNX session and Tokenizer.
     */
    fun initialize(modelPath: String, vocabPath: String) {
        try {
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e("EmbeddingManager", "Model file not found at $modelPath")
                return
            }
            ortSession = ortEnv.createSession(modelPath, OrtSession.SessionOptions())
            tokenizer.initialize(vocabPath)
            Log.d("EmbeddingManager", "ONNX Session and Tokenizer initialized successfully")
        } catch (e: Exception) {
            Log.e("EmbeddingManager", "Failed to initialize ONNX session", e)
        }
    }

    /**
     * Generates an embedding for the given text using the ONNX model.
     */
    fun generateEmbedding(text: String): FloatArray {
        val session = ortSession ?: return FloatArray(embeddingDimension)
        val tokenIds = tokenizer.tokenize(text)
        if (tokenIds.isEmpty()) return FloatArray(embeddingDimension)

        return try {
            val shape = longArrayOf(1, tokenIds.size.toLong())
            val tokenBuffer = LongBuffer.wrap(tokenIds)
            
            // Create input tensors
            val inputIds = OnnxTensor.createTensor(ortEnv, tokenBuffer, shape)
            
            // Attention mask (all 1s for now)
            val maskBuffer = LongBuffer.wrap(LongArray(tokenIds.size) { 1L })
            val attentionMask = OnnxTensor.createTensor(ortEnv, maskBuffer, shape)
            
            // Type IDs (all 0s)
            val typeBuffer = LongBuffer.wrap(LongArray(tokenIds.size) { 0L })
            val tokenTypeIds = OnnxTensor.createTensor(ortEnv, typeBuffer, shape)

            val inputs = mapOf(
                "input_ids" to inputIds,
                "attention_mask" to attentionMask,
                "token_type_ids" to tokenTypeIds
            )

            session.run(inputs).use { results ->
                val lastHiddenState = results[0].value as Array<Array<FloatArray>>
                // Mean Pooling: average the token embeddings
                val embedding = FloatArray(embeddingDimension)
                val seqLen = tokenIds.size
                for (i in 0 until seqLen) {
                    for (j in 0 until embeddingDimension) {
                        embedding[j] += lastHiddenState[0][i][j]
                    }
                }
                for (j in 0 until embeddingDimension) {
                    embedding[j] /= seqLen.toFloat()
                }
                normalize(embedding)
            }
        } catch (e: Exception) {
            Log.e("EmbeddingManager", "Inference failed", e)
            FloatArray(embeddingDimension)
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-9) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    /**
     * Calculates cosine similarity between two vectors.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
        }
        return dotProduct // Vectors are normalized, so dot product is cosine similarity
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}
