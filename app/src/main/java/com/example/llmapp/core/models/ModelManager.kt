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
            description = "The absolute latest generation from Google (April 2026). Ultra-smart and optimized for mobile.",
            url = "https://huggingface.co/google/gemma-4-e2b-it-gpu-int4/resolve/main/gemma-4-e2b-it-gpu-int4.bin",
            fileName = "gemma-4-e2b-it.bin",
            size = "1.8 GB"
        ),
        LlmModelInfo(
            name = "Gemma 2 2B (IT)",
            description = "Latest high-performance small model from Google.",
            url = "https://huggingface.co/google/gemma-2-2b-it-gpu-int4/resolve/main/gemma-2-2b-it-gpu-int4.bin",
            fileName = "gemma-2-2b-it.bin",
            size = "1.6 GB"
        ),
        LlmModelInfo(
            name = "Gemma 1.1 2B (IT)",
            description = "Optimized for speed and efficiency.",
            url = "https://huggingface.co/google/gemma-1.1-2b-it-gpu-int4/resolve/main/gemma-1.1-2b-it-gpu-int4.bin",
            fileName = "gemma-1.1-2b-it.bin",
            size = "1.4 GB"
        ),
        LlmModelInfo(
            name = "Falcon 1B",
            description = "Ultra-lightweight model for low-end devices.",
            url = "https://huggingface.co/tiiuae/falcon-1b-it-gpu-int4/resolve/main/falcon-1b-it-gpu-int4.bin",
            fileName = "falcon-1b-it.bin",
            size = "0.9 GB"
        )
    )

    fun getDownloadedModels(): List<File> {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return emptyList()
        return dir.listFiles { file -> file.extension == "bin" }?.toList() ?: emptyList()
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
