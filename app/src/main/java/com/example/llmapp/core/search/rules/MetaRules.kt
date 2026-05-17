package com.example.llmapp.core.search.rules

/**
 * Isolated rules for conversational bypass checks and user-driven search overrides.
 */
object MetaRules {
    val BYPASS_PATTERNS = listOf(
        Regex("(?i)^have you\\b"),
        Regex("(?i)^did you\\b"),
        Regex("(?i)^are you\\b"),
        Regex("(?i)^why did you\\b"),
        Regex("(?i)^why didn't you\\b"),
        Regex("(?i)^why did not you\\b"),
        Regex("(?i)^you are wrong\\b"),
        Regex("(?i)^you got it wrong\\b"),
        Regex("(?i)^that is wrong\\b"),
        Regex("(?i)^that is incorrect\\b"),
        Regex("(?i)^check again\\b"),
        Regex("(?i)^search again\\b"),
        Regex("(?i)^try again\\b"),
        Regex("(?i)^verify this\\b"),
        Regex("(?i)^verify that\\b"),
        Regex("(?i)^verify again\\b"),
        Regex("(?i)^double check\\b")
    )

    val USER_FORCED_KEYWORDS = setOf(
        "search this", "look online", "check the web", "search on internet",
        "search the web", "google this", "find online", "look up online"
    )
}
