package com.example.llmapp.core.memory

import com.example.llmapp.core.orchestrator.RankedMemory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Temporal conflict resolution. Distinguishes between:
 * - Evolution: "lived in Jaunpur (2024) → Mumbai (2026)" — temporal change, not contradiction
 * - Contradiction: same timestamp, conflicting values — actual conflict
 */
class MemoryConflictResolver {

    private val dateFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

    /**
     * Analyzes ranked memories for conflicts/evolutions.
     * @return Pair of (unchanged ranked list, conflict descriptions)
     */
    fun resolve(ranked: List<RankedMemory>): Pair<List<RankedMemory>, List<String>> {
        val conflicts = mutableListOf<String>()

        // Group by subject (first meaningful words of the fact)
        val grouped = ranked.groupBy { extractSubject(it.fact) }

        for ((subject, memories) in grouped) {
            if (subject.isBlank() || memories.size < 2) continue

            val sorted = memories.sortedBy { it.timestamp }
            val oldest = sorted.first()
            val newest = sorted.last()

            // Only flag if facts differ and have different timestamps
            if (oldest.fact != newest.fact) {
                val timeDiffDays = (newest.timestamp - oldest.timestamp) / (24 * 3600_000L)

                if (timeDiffDays > 7) {
                    // Temporal evolution: facts changed over time (not a contradiction)
                    val oldDate = dateFormat.format(Date(oldest.timestamp))
                    val newDate = dateFormat.format(Date(newest.timestamp))
                    conflicts.add("Evolution ($subject): ${oldest.fact} ($oldDate) → ${newest.fact} ($newDate)")
                } else {
                    // True contradiction: conflicting facts within a short timeframe
                    conflicts.add("Conflict ($subject): \"${oldest.fact}\" vs \"${newest.fact}\"")
                }
            }
        }

        return ranked to conflicts
    }

    /**
     * Extracts a simple subject from a fact string for grouping.
     * Uses the first 3 meaningful words as a subject key.
     */
    private fun extractSubject(fact: String): String {
        return fact.lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .split("\\s+".toRegex())
            .filter { it.length > 2 }
            .take(3)
            .joinToString(" ")
    }
}
