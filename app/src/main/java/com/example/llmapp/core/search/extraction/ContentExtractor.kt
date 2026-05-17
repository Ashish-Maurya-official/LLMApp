package com.example.llmapp.core.search.extraction

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Extracts readable article content from raw HTML using Jsoup.
 * Removes boilerplate, ads, navbars, and scripts.
 */
object ContentExtractor {

    /**
     * @return Extracted plain text content, or empty string if parsing fails.
     */
    fun extractArticle(html: String): String {
        if (html.isBlank()) return ""

        return try {
            val doc: Document = Jsoup.parse(html)

            // 1. Remove obvious noise tags
            doc.select("script, style, noscript, nav, footer, header, aside, .ad, .ads, .advertisement, #cookie-banner, .popup").remove()

            // 2. Try to find the main article container
            val articleContainer = doc.select("article, main, .post-content, .article-body, #main-content").firstOrNull()
            
            val elementsToRead = if (articleContainer != null) {
                // If we found a specific article tag, just read its paragraphs
                articleContainer.select("p, h1, h2, h3, h4, li")
            } else {
                // Fallback: just read all paragraphs in the document
                doc.select("p, h1, h2, h3, h4, li")
            }

            // 3. Extract text
            val sb = java.lang.StringBuilder()
            for (element in elementsToRead) {
                val text = element.text().trim()
                if (text.isNotBlank()) {
                    sb.append(text).append("\n\n")
                }
            }

            sb.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Compresses extracted text to fit within mobile LLM context windows (e.g., Gemini Nano).
 */
object CompressionPipeline {
    private const val MAX_PAGE_CHARS = 3000
    private const val MAX_PARAGRAPHS = 8

    /**
     * Truncates text by paragraph count and absolute character limit.
     */
    fun compress(text: String): String {
        if (text.isBlank()) return ""

        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
        val limitedParagraphs = paragraphs.take(MAX_PARAGRAPHS).joinToString("\n\n")

        return if (limitedParagraphs.length > MAX_PAGE_CHARS) {
            limitedParagraphs.substring(0, MAX_PAGE_CHARS) + "... [content truncated for length]"
        } else {
            limitedParagraphs
        }
    }
}
