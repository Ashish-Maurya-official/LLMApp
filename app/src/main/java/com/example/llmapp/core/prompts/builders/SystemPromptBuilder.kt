package com.example.llmapp.core.prompts.builders

import com.example.llmapp.core.prompts.models.PromptContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds the base system prompt containing core identity, personalization traits, and mobile formatting guidelines.
 */
object SystemPromptBuilder {

    fun build(ctx: PromptContext): String {
        val sb = StringBuilder("You are a helpful AI assistant.")
        
        if (ctx.systemPromptOverride.isNotBlank()) {
            sb.append("\n${ctx.systemPromptOverride}")
        }
        
        val hasUserInfo = listOf(ctx.userName, ctx.userDob, ctx.userLocation, ctx.userBio).any { it.isNotBlank() }
        if (hasUserInfo) {
            sb.append("\n\nUSER INFORMATION:")
            if (ctx.userName.isNotBlank()) sb.append("\n- Name: ${ctx.userName}")
            if (ctx.userDob.isNotBlank()) sb.append("\n- DOB: ${ctx.userDob}")
            if (ctx.userLocation.isNotBlank()) sb.append("\n- Location: ${ctx.userLocation}")
            if (ctx.userBio.isNotBlank()) sb.append("\n- Bio: ${ctx.userBio}")
        }

        val dt = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
        sb.append("\n\nCurrent date/time: $dt")

        // Dynamic Tool Access Protocol
        sb.append("\n\n## Tool Access Protocol")
        sb.append("\n- You have access to a real-time web search engine.")
        sb.append("\n- If the user asks for current information (e.g. weather, news, latest events, prices, or sports scores) that you do not possess, or if the user questions/challenges your facts (e.g., \"are you sure?\", \"did you check current info?\", \"verify this\"), you MUST output EXACTLY the following token on its own line:")
        sb.append("\n  [SEARCH_NEEDED: <your search query>]")
        sb.append("\n  Do NOT explain what is missing, and do NOT write anything else on that line.")
        sb.append("\n\nExample:")
        sb.append("\n  User: \"What is the weather in Delhi right now?\"")
        sb.append("\n  You: [SEARCH_NEEDED: current weather in Delhi]")
        sb.append("\n\nExample:")
        sb.append("\n  User: \"did you checked current info\"")
        sb.append("\n  You: [SEARCH_NEEDED: current Prime Minister of Bangladesh]")

        sb.append("\n\nFormatting: use Markdown — **bold**, bullet lists, ### headers, tables, `code`. Keep responses concise for mobile.")
        
        return sb.toString()
    }
}
