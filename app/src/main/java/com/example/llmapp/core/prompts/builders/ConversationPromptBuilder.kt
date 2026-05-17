package com.example.llmapp.core.prompts.builders

import com.example.llmapp.ChatMessage
import com.example.llmapp.core.prompts.models.PromptContext

/**
 * Serializes the conversation message history using specific Gemma turns.
 * Integrates context window slicing and token degradation capping dynamically.
 */
object ConversationPromptBuilder {

    fun build(
        messages: List<ChatMessage>,
        pendingUserText: String,
        ctx: PromptContext
    ): String {
        val sb = StringBuilder()
        
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
        
        sb.append("<start_of_turn>user\n${pendingUserText.trim()}<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        
        return sb.toString()
    }
}
