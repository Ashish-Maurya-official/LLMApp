package com.example.llmapp.core.models

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

data class LlmModelInfo(
    val name: String,
    val description: String,
    val url: String,
    val fileName: String,
    val size: String
)

class ModelManager(private val context: Context) {

    val availableModels = listOf(
        LlmModelInfo(
            name = "Gemma 4 E2B (IT)",
            description = "The absolute latest generation (April 2026). Optimized for LiteRT-LM with top reasoning.",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            fileName = "gemma-4-e2b.litertlm",
            size = "1.8 GB"
        ),
        LlmModelInfo(
    name = "Gemma 4 E2B (IT)",
    description = "The absolute latest generation (April 2026). Optimized for LiteRT-LM with top reasoning.",
    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
    fileName = "gemma-4-e2b.litertlm",
    size = "2.6 GB"
),
LlmModelInfo(
    name = "Gemma 4 E4B (IT)",
    description = "Larger, highly capable sibling to E2B. Stronger reasoning capabilities at the cost of higher RAM footprint.",
    url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
    fileName = "gemma-4-e4b.litertlm",
    size = "3.7 GB"
),
LlmModelInfo(
    name = "Qwen 2.5 1.5B (IT)",
    description = "Excellent balance of speed and capability. Features 8-bit quantization and multi-prefill sequence packaging.",
    url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
    fileName = "qwen-2.5-1.5b-instruct.litertlm",
    size = "1.6 GB"
),
LlmModelInfo(
    name = "DeepSeek R1 Distill Qwen 1.5B",
    description = "Edge-optimized distillation of DeepSeek's reasoning model, packaged specifically for the LiteRT-LM runtime.",
    url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
    fileName = "deepseek-r1-distill-qwen-1.5b.litertlm",
    size = "1.8 GB"
),
LlmModelInfo(
    name = "Phi-4-Mini Instruct",
    description = "Microsoft's highly optimized SLM for mobile. 8-bit quantized configuration with a 4096 context window.",
    url = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
    fileName = "phi-4-mini-instruct.litertlm",
    size = "3.9 GB"
),
    )

    fun getDownloadedModels(): List<File> {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        return dir.listFiles { file -> 
            val ext = file.extension
            ext == "bin" || ext == "litertlm" || ext == "onnx" || ext == "txt"
        }?.toList() ?: emptyList()
    }

    fun downloadModel(model: LlmModelInfo) {
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Downloading ${model.name}")
            .setDescription("LLM Model file for Agentic AI")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
    }
    
    fun getModelPath(fileName: String): String {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        return file.absolutePath
    }
}
