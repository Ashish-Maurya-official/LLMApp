package com.example.llmapp.ui.models.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL

data class AvailableModel(
    val name: String,
    val description: String,
    val size: String,
    val url: String,
    val fileName: String,
    val tags: List<String> = emptyList()
)

val fallbackModels = listOf(
    AvailableModel(
        "Gemma 4 E2B (IT)",
        "The absolute latest generation (April 2026). Optimized for LiteRT-LM with top reasoning.",
        "1.8 GB",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        "gemma-4-e2b.litertlm",
        listOf("LiteRT", "NEW", "2026")
    ),
    AvailableModel(
        "Gemma 2 2B (IT)",
        "Verified high-performance model for mobile. Fast, stable, and smart.",
        "1.4 GB",
        "https://huggingface.co/litert-community/gemma-2-2b-it-litert-lm/resolve/main/gemma-2-2b-it.litertlm",
        "gemma-2-2b.litertlm",
        listOf("LiteRT", "Stable")
    ),
    AvailableModel(
        "Falcon 1B",
        "Ultra-lightweight model. Works on any device without lag.",
        "0.9 GB",
        "https://huggingface.co/tiiuae/falcon-1b-it-gpu-int4/resolve/main/falcon-1b-it-gpu-int4.bin",
        "falcon-1b-it.bin",
        listOf("Lightweight")
    )
)

suspend fun fetchRemoteModels(): List<AvailableModel>? = withContext(Dispatchers.IO) {
    try {
        val json = URL("https://raw.githubusercontent.com/Ashish-Maurya-official/LLMApp/main/models.json")
            .readText(Charsets.UTF_8)
        val arr = JSONArray(json)
        val result = mutableListOf<AvailableModel>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val tagsArr = obj.optJSONArray("tags")
            val tags = if (tagsArr != null) List(tagsArr.length()) { tagsArr.getString(it) } else emptyList()
            result.add(
                AvailableModel(
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    size = obj.getString("size"),
                    url = obj.getString("url"),
                    fileName = obj.getString("fileName"),
                    tags = tags
                )
            )
        }
        result
    } catch (e: Exception) {
        null
    }
}
