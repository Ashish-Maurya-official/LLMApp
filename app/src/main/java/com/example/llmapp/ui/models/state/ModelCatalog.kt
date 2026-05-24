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
        "Qwen 2.5 0.5B (Orchestrator)",
        "Ultra-fast lightweight model optimized for cognitive routing, json structured output, and intent classification.",
        "0.6 GB",
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task?download=true",
        "qwen-2.5-0.5b-instruct.task",
        listOf("LiteRT", "Orchestrator", "Qwen")
    ),
    AvailableModel(
        "Gemma 4 E2B (IT)",
        "The absolute latest generation (April 2026). Optimized for LiteRT-LM with top reasoning.",
        "2.6 GB",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
        "gemma-4-e2b.litertlm",
        listOf("LiteRT", "NEW", "2026")
    ),
    AvailableModel(
        "Gemma 4 E4B (IT)",
        "Larger, highly capable sibling to E2B. Stronger reasoning capabilities at the cost of higher RAM footprint.",
        "3.7 GB",
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
        "gemma-4-e4b.litertlm",
        listOf("LiteRT", "NEW", "2026")
    ),
    AvailableModel(
        "Qwen 2.5 1.5B (IT)",
        "Excellent balance of speed and capability. Features 8-bit quantization and multi-prefill sequence packaging.",
        "1.6 GB",
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
        "qwen-2.5-1.5b-instruct.litertlm",
        listOf("LiteRT", "Qwen")
    ),
    AvailableModel(
        "DeepSeek R1 Distill Qwen 1.5B",
        "Edge-optimized distillation of DeepSeek's reasoning model, packaged specifically for the LiteRT-LM runtime.",
        "1.8 GB",
        "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
        "deepseek-r1-distill-qwen-1.5b.litertlm",
        listOf("LiteRT", "DeepSeek", "Reasoning")
    ),
    AvailableModel(
        "Phi-4-Mini Instruct",
        "Microsoft's highly optimized SLM for mobile. 8-bit quantized configuration with a 4096 context window.",
        "3.9 GB",
        "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm?download=true",
        "phi-4-mini-instruct.litertlm",
        listOf("LiteRT", "Phi-4")
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
