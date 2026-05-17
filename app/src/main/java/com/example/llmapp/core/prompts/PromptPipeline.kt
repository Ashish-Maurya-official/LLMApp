package com.example.llmapp.core.prompts

import com.example.llmapp.ChatMessage
import com.example.llmapp.core.prompts.builders.SystemPromptBuilder
import com.example.llmapp.core.prompts.builders.ConversationPromptBuilder
import com.example.llmapp.core.prompts.builders.SearchPromptBuilder
import com.example.llmapp.core.prompts.builders.WeatherPromptBuilder
import com.example.llmapp.core.prompts.models.PromptContext
import com.example.llmapp.core.search.SearchType

/**
 * Unified compilation pipeline for assembling modular, specialized prompts.
 * Eliminates prompt bloat by dynamically injecting rules only when required.
 */
object PromptPipeline {

    /**
     * Compiles the absolute minimum prompt for direct, conversational responses.
     * Contains ZERO search rulebooks, sentinels, or weather rules.
     */
    fun buildNormalPrompt(
        pendingUserText: String,
        messages: List<ChatMessage>,
        ctx: PromptContext
    ): String {
        val sb = StringBuilder()
        
        // 1. System Identity and Preferences
        sb.append("<start_of_turn>user\n")
        sb.append(SystemPromptBuilder.build(ctx))
        sb.append("<end_of_turn>\n<start_of_turn>model\nUnderstood. I am ready to help.<end_of_turn>\n")
        
        // 2. Sliding Conversation History
        sb.append(ConversationPromptBuilder.build(messages, pendingUserText, ctx))
        
        return sb.toString()
    }

    /**
     * Compiles a highly target-optimized prompt for search results.
     * Inject ONLY the guidelines and constraints mapping to the specific SearchType.
     */
    fun buildPromptWithContext(
        pendingUserText: String,
        searchContext: String,
        searchType: SearchType,
        messages: List<ChatMessage>,
        ctx: PromptContext
    ): String {
        val sb = StringBuilder()
        
        // 1. Base System Identity and Preferences
        sb.append("<start_of_turn>user\n")
        sb.append(SystemPromptBuilder.build(ctx))
        sb.append("<end_of_turn>\n<start_of_turn>model\nUnderstood. I am ready to help.<end_of_turn>\n")
        
        // 2. Conversation History
        val limit = if (ctx.activeDegradationLevel >= com.example.llmapp.core.runtime.CognitiveEvent.DegradationLevel.SUMMARIZE_CONTEXT) {
            3
        } else {
            ctx.contextLimit
        }
        val cap = ctx.perMessageCap
        
        messages.dropLast(1).takeLast(limit).forEach { msg ->
            val content = msg.text.take(cap)
            if (content.isNotBlank()) {
                if (msg.isUser) {
                    sb.append("<start_of_turn>user\n$content<end_of_turn>\n")
                } else {
                    sb.append("<start_of_turn>model\n$content<end_of_turn>\n")
                }
            }
        }
        
        // 3. Specialized Search Context turn
        sb.append("<start_of_turn>user\n")
        
        // Build search block with domain-specific behavioral policy
        val searchBlock = SearchPromptBuilder.build(pendingUserText, searchContext, searchType)
        sb.append(searchBlock)
        
        // Build weather-card JSON rule block if SearchType is WEATHER
        if (searchType == SearchType.WEATHER) {
            sb.append(WeatherPromptBuilder.build())
        }
        
        sb.append("\nUser's question: $pendingUserText\n")
        sb.append("<end_of_turn>\n<start_of_turn>model\n")
        
        return sb.toString()
    }
}
