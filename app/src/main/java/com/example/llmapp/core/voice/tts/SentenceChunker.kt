package com.example.llmapp.core.voice.tts

/**
 * Handles sentence boundary detection and markdown text cleaning
 * before text reaches the TTS engine.
 */
object SentenceChunker {

    // Abbreviations that should NOT trigger a sentence split
    private val ABBREVIATIONS = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "sr.", "jr.",
        "st.", "ave.", "inc.", "ltd.", "co.", "corp.", "vs.",
        "e.g.", "i.e.", "etc.", "approx.", "fig.", "no."
    )

    // Sentence-ending sequences (must be followed by whitespace or end of string)
    private val SENTENCE_ENDINGS = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n", ".\"", "!\"", "?\"")

    /**
     * Strips markdown syntax that would be read aloud awkwardly by TTS.
     * E.g., "**bold**" → "bold", "# Header" → "Header", "`code`" → "code"
     */
    fun stripMarkdown(text: String): String {
        return text
            // Weather card data — skip entirely
            .replace(Regex("\\[WEATHER_CARD:\\{[\\s\\S]*?\\}\\]"), "")
            // Headers: # H1, ## H2, etc.
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            // Bold/italic: **text**, *text*, __text__, _text_
            .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
            .replace(Regex("_{1,2}([^_]+)_{1,2}"), "$1")
            // Inline code
            .replace(Regex("`([^`]+)`"), "$1")
            // Code blocks (read language tag + first line, skip rest)
            .replace(Regex("```[\\w]*\\n([\\s\\S]*?)```")) { match ->
                val lines = match.groupValues[1].lines()
                if (lines.isNotEmpty()) "Code: ${lines.first()}." else ""
            }
            // Bullet lists: "- item" → "item"
            .replace(Regex("^[\\-\\*]\\s+", RegexOption.MULTILINE), "")
            // Numbered lists: "1. item" → "item"
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            // Links: [text](url) → "text"
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            // Blockquotes
            .replace(Regex("^>\\s+", RegexOption.MULTILINE), "")
            // Horizontal rules
            .replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
            // Table rows — keep the cell text, strip pipes
            .replace(Regex("\\|[\\s-]+\\|"), "")
            .replace("|", ", ")
            // Collapse multiple spaces/newlines
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Finds the index of the first valid sentence boundary in [text].
     * Returns -1 if no complete sentence is found yet.
     */
    fun findBoundary(text: String): Int {
        var earliest = Int.MAX_VALUE

        for (ending in SENTENCE_ENDINGS) {
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(ending, searchFrom)
                if (idx < 0) break
                val segmentUpTo = text.substring(0, idx + 1).lowercase()
                val isAbbreviation = ABBREVIATIONS.any { segmentUpTo.endsWith(it) }
                if (!isAbbreviation) {
                    earliest = minOf(earliest, idx + ending.length)
                    break
                }
                searchFrom = idx + 1
            }
        }

        // Also split on ":\n" boundaries (common in list intros)
        val colonNewline = text.indexOf(":\n")
        if (colonNewline >= 0) earliest = minOf(earliest, colonNewline + 2)

        return if (earliest == Int.MAX_VALUE) -1 else earliest
    }

    /**
     * Splits [text] into sentence-length chunks suitable for TTS.
     * Handles the case where a final incomplete sentence is left as-is.
     */
    fun splitIntoSentences(text: String): List<String> {
        val cleaned = stripMarkdown(text)
        val result = mutableListOf<String>()
        var remaining = cleaned

        while (remaining.isNotBlank()) {
            val boundary = findBoundary(remaining)
            if (boundary > 0) {
                val sentence = remaining.substring(0, boundary).trim()
                if (sentence.isNotBlank()) result.add(sentence)
                remaining = remaining.substring(boundary)
            } else {
                // No boundary found — emit everything as the final chunk
                val last = remaining.trim()
                if (last.isNotBlank()) result.add(last)
                break
            }
        }

        return result
    }
}
