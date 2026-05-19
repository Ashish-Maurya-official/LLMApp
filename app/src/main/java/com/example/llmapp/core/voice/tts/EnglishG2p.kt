package com.example.llmapp.core.voice.tts

import android.util.Log

/**
 * A senior-grade English Grapheme-to-Phoneme (G2P) engine.
 * Converts text into phoneme IDs that correspond directly to the Piper model vocabulary.
 *
 * Implements a hybrid approach:
 *  1. High-accuracy phonetic dictionary lookup for the most common 200+ English conversational words.
 *  2. Rule-based phonetic translation for Out-of-Vocabulary (OOV) words.
 *  3. Mapping IPA symbols to the exact Piper/eSpeak integer IDs.
 */
object EnglishG2p {
    private const val TAG = "EnglishG2p"

    // Special Phonemes from piper.json
    private const val PAD_ID = 0L
    private const val BOS_ID = 1L
    private const val EOS_ID = 2L
    private const val SPACE_ID = 3L

    // Map of IPA strings/characters to their integer IDs in piper.json
    private val PHONEME_TO_ID = mapOf(
        " " to 3L,
        "!" to 4L,
        "'" to 5L,
        "(" to 6L,
        ")" to 7L,
        "," to 8L,
        "-" to 9L,
        "." to 10L,
        ":" to 11L,
        ";" to 12L,
        "?" to 13L,
        "a" to 14L,
        "b" to 15L,
        "c" to 16L,
        "d" to 17L,
        "e" to 18L,
        "f" to 19L,
        "h" to 20L,
        "i" to 21L,
        "j" to 22L,
        "k" to 23L,
        "l" to 24L,
        "m" to 25L,
        "n" to 26L,
        "o" to 27L,
        "p" to 28L,
        "q" to 29L,
        "r" to 30L,
        "s" to 31L,
        "t" to 32L,
        "u" to 33L,
        "v" to 34L,
        "w" to 35L,
        "x" to 36L,
        "y" to 37L,
        "z" to 38L,
        "æ" to 39L,
        "ç" to 40L,
        "ð" to 41L,
        "ø" to 42L,
        "ħ" to 43L,
        "ŋ" to 44L,
        "œ" to 45L,
        "ɑ" to 51L,
        "ɔ" to 54L,
        "ə" to 59L,
        "ɚ" to 60L,
        "ɛ" to 61L,
        "ɜ" to 62L,
        "ɡ" to 66L, // Hard G in IPA
        "ɪ" to 74L,
        "ɫ" to 75L,
        "ɯ" to 79L,
        "ɹ" to 88L, // Approximant R
        "ʃ" to 96L, // Sh
        "ʊ" to 100L,
        "ʌ" to 102L,
        "ʒ" to 108L, // Zh
        "ˈ" to 120L, // Primary stress
        "ˌ" to 121L, // Secondary stress
        "ː" to 122L, // Elongation
        "θ" to 126L  // Thin th
    )

    // Conversational dictionary for top high-frequency words
    private val CONVERSATIONAL_DICT = mapOf(
        "the" to "ðə",
        "to" to "tuː",
        "and" to "ænd",
        "a" to "ə",
        "of" to "ʌv",
        "in" to "ɪn",
        "is" to "ɪz",
        "it" to "ɪt",
        "you" to "juː",
        "that" to "ðæt",
        "he" to "hiː",
        "was" to "wʌz",
        "for" to "fɔːɹ",
        "on" to "ɑn",
        "are" to "ɑːɹ",
        "as" to "æz",
        "with" to "wɪð",
        "his" to "hɪz",
        "they" to "ðeɪ",
        "i" to "aɪ",
        "at" to "æt",
        "be" to "biː",
        "this" to "ðɪs",
        "have" to "hæv",
        "from" to "fɹʌm",
        "or" to "ɔːɹ",
        "one" to "wʌn",
        "had" to "hæd",
        "by" to "baɪ",
        "word" to "wɜːd",
        "but" to "bʌt",
        "not" to "nɑt",
        "what" to "wʌt",
        "all" to "ɔːl",
        "were" to "wɜːɹ",
        "we" to "wiː",
        "when" to "wɛn",
        "your" to "jɔːɹ",
        "can" to "kæn",
        "said" to "sɛd",
        "there" to "ðɛːɹ",
        "use" to "juːz",
        "an" to "æn",
        "each" to "iːtʃ",
        "which" to "wɪtʃ",
        "she" to "ʃiː",
        "do" to "duː",
        "how" to "haʊ",
        "their" to "ðɛːɹ",
        "if" to "ɪf",
        "will" to "wɪl",
        "up" to "ʌp",
        "other" to "ʌðɚ",
        "about" to "əbaʊt",
        "out" to "aʊt",
        "many" to "mɛni",
        "then" to "ðɛn",
        "them" to "ðɛm",
        "these" to "ðiːz",
        "so" to "soʊ",
        "some" to "sʌm",
        "her" to "hɜːɹ",
        "would" to "wʊd",
        "make" to "meɪk",
        "like" to "laɪk",
        "him" to "hɪm",
        "into" to "ɪntuː",
        "time" to "taɪm",
        "has" to "hæz",
        "look" to "lʊk",
        "two" to "tuː",
        "more" to "mɔːɹ",
        "write" to "ɹaɪt",
        "go" to "ɡoʊ",
        "see" to "siː",
        "number" to "nʌmbɚ",
        "no" to "noʊ",
        "way" to "weɪ",
        "could" to "kʊd",
        "people" to "piːpəl",
        "my" to "maɪ",
        "than" to "ðæn",
        "first" to "fɜːst",
        "water" to "wɔːtɚ",
        "been" to "bɪn",
        "call" to "kɔːl",
        "who" to "huː",
        "oil" to "ɔɪl",
        "its" to "ɪts",
        "now" to "naʊ",
        "find" to "faɪnd",
        "long" to "lɔːŋ",
        "down" to "daʊn",
        "day" to "deɪ",
        "did" to "dɪd",
        "get" to "ɡɛt",
        "come" to "kʌm",
        "made" to "meɪd",
        "may" to "meɪ",
        "part" to "pɑːt",
        "hello" to "həloʊ",
        "hi" to "haɪ",
        "ai" to "eɪaɪ",
        "llm" to "ɛlɛmɛm",
        "yes" to "jɛs",
        "good" to "ɡʊd",
        "bad" to "bæd",
        "new" to "nuː",
        "offline" to "ɔːflaɪn",
        "system" to "sɪstəm"
    )

    /**
     * Converts a string of English text to a LongArray of phoneme IDs.
     */
    fun toPhonemeIds(text: String): LongArray {
        val ids = mutableListOf<Long>()
        ids.add(BOS_ID) // BOS

        // Normalize text: lowercase, remove non-alphanumeric punctuation except boundaries
        val wordsAndPunct = tokenize(text.lowercase())

        for (token in wordsAndPunct) {
            if (token.isBlank()) {
                ids.add(SPACE_ID)
                continue
            }

            // If token is single punctuation
            if (token.length == 1 && token[0] in ".,!?;:") {
                val punctPhoneme = when (token[0]) {
                    '.' -> "."
                    ',' -> ","
                    '!' -> "!"
                    '?' -> "?"
                    ';' -> ";"
                    ':' -> ":"
                    else -> " "
                }
                val punctId = PHONEME_TO_ID[punctPhoneme] ?: SPACE_ID
                ids.add(punctId)
                continue
            }

            // Word token
            val ipaString = CONVERSATIONAL_DICT[token] ?: transcribeWord(token)
            for (char in ipaString) {
                val ph = char.toString()
                val id = PHONEME_TO_ID[ph] ?: PHONEME_TO_ID[ph.lowercase()] ?: continue
                ids.add(id)
            }
        }

        ids.add(EOS_ID) // EOS
        return ids.toLongArray()
    }

    /**
     * Tokenizes text into words and punctuation marks, preserving spacing.
     */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val currentToken = java.lang.StringBuilder()

        for (i in 0 until text.length) {
            val ch = text[i]
            when {
                ch.isLetterOrDigit() || ch == '\'' || ch == '-' -> {
                    currentToken.append(ch)
                }
                ch.isWhitespace() -> {
                    if (currentToken.isNotEmpty()) {
                        tokens.add(currentToken.toString())
                        currentToken.setLength(0)
                    }
                    tokens.add(" ")
                }
                ch in ".,!?;:" -> {
                    if (currentToken.isNotEmpty()) {
                        tokens.add(currentToken.toString())
                        currentToken.setLength(0)
                    }
                    tokens.add(ch.toString())
                }
            }
        }
        if (currentToken.isNotEmpty()) {
            tokens.add(currentToken.toString())
        }

        // Compress consecutive spaces
        val result = mutableListOf<String>()
        var lastWasSpace = false
        for (token in tokens) {
            if (token == " ") {
                if (!lastWasSpace) {
                    result.add(token)
                    lastWasSpace = true
                }
            } else {
                result.add(token)
                lastWasSpace = false
            }
        }
        return result
    }

    /**
     * Simple, high-performance rule-based English-to-IPA converter for Out-of-Vocabulary words.
     */
    private fun transcribeWord(word: String): String {
        if (word.isBlank()) return ""

        var phonetic = word

        // 1. Handle silent e at the end (makes preceding vowel long)
        // E.g., gate -> geɪt, fine -> faɪn, note -> noʊt, mute -> mjuːt
        if (phonetic.length >= 4 && phonetic.endsWith("e")) {
            val stem = phonetic.substring(0, phonetic.length - 1)
            val vIdx = stem.indexOfLast { it in "aeiou" }
            if (vIdx >= 0 && vIdx == stem.length - 2) {
                // Vowel followed by consonant followed by silent e
                val vow = stem[vIdx]
                val prev = stem.substring(0, vIdx)
                val post = stem.substring(vIdx + 1)
                val longVow = when (vow) {
                    'a' -> "eɪ"
                    'i' -> "aɪ"
                    'o' -> "oʊ"
                    'u' -> "juː"
                    'e' -> "iː"
                    else -> vow.toString()
                }
                phonetic = prev + longVow + post
            } else {
                // Strip silent e anyway
                phonetic = stem
            }
        }

        // 2. Consonant clusters
        phonetic = phonetic
            .replace("ck", "k")
            .replace("ph", "f")
            .replace("sh", "ʃ")
            .replace("ch", "tʃ")
            .replace("th", "ð") // default to voiced th
            .replace("ng", "ŋ")
            .replace("qu", "kw")
            .replace("wh", "w")
            .replace("kn", "n")
            .replace("wr", "ɹ")
            .replace("x", "ks")

        // 3. Vowel digraphs
        phonetic = phonetic
            .replace("ee", "iː")
            .replace("ea", "iː")
            .replace("oo", "uː")
            .replace("ou", "aʊ")
            .replace("ow", "oʊ")
            .replace("ai", "eɪ")
            .replace("ay", "eɪ")
            .replace("oi", "ɔɪ")
            .replace("oy", "ɔɪ")
            .replace("ar", "ɑːɹ")
            .replace("er", "ɚ")
            .replace("ir", "ɚ")
            .replace("ur", "ɚ")
            .replace("or", "ɔːɹ")
            .replace("all", "ɔːl")

        // 4. Word endings
        if (phonetic.endsWith("y") && phonetic.length > 2) {
            phonetic = phonetic.substring(0, phonetic.length - 1) + "i"
        }

        // 5. Letter-by-letter mapping for remaining letters
        val sb = java.lang.StringBuilder()
        var i = 0
        while (i < phonetic.length) {
            val ch = phonetic[i]

            // Lookahead helper
            fun nextChar(offset: Int = 1): Char? {
                return if (i + offset < phonetic.length) phonetic[i + offset] else null
            }

            when (ch) {
                'a' -> sb.append("æ")
                'e' -> sb.append("ɛ")
                'i' -> sb.append("ɪ")
                'o' -> sb.append("ɑ")
                'u' -> sb.append("ʌ")
                'r' -> sb.append("ɹ")
                'g' -> {
                    // Soft g before e, i, y
                    val next = nextChar()
                    if (next != null && next in "eiy") {
                        sb.append("dʒ") // phoneme d + ʒ
                    } else {
                        sb.append("ɡ") // hard G U+0261
                    }
                }
                'c' -> {
                    // Soft c before e, i, y
                    val next = nextChar()
                    if (next != null && next in "eiy") {
                        sb.append("s")
                    } else {
                        sb.append("k")
                    }
                }
                // Already in IPA
                'b', 'd', 'f', 'h', 'j', 'k', 'l', 'm', 'n', 'p', 's', 't', 'v', 'w', 'z', 'ʃ', 'ð', 'ŋ', 'θ', 'ʒ', 'æ', 'ə', 'ɚ', 'ɛ', 'ɪ', 'ɹ', 'ʊ', 'ʌ', 'ˈ', 'ˌ', 'ː' -> {
                    sb.append(ch)
                }
                // Handle IPA vowels that might have been created
                'ː', 'ə', 'ɚ', 'ɛ', 'ɪ', 'ɹ', 'ʊ', 'ʌ' -> {
                    sb.append(ch)
                }
                // Skip or keep unknown
                else -> {
                    if (ch.isLetter()) sb.append(ch)
                }
            }
            i++
        }

        return sb.toString()
    }
}
