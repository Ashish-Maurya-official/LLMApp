package com.example.llmapp.ui.chat.utils

/** Split pipe cells from a single markdown table row line. */
fun parseTableRow(line: String): List<String> {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|")) return listOf(trimmed)
    return trimmed.split("|")
        .drop(1)
        .let { cells -> if (cells.lastOrNull()?.isBlank() == true) cells.dropLast(1) else cells }
        .map { it.trim() }
}

/** True for rows like `| --- | :--- | ---: |` */
fun isSeparatorRow(line: String): Boolean {
    val content = line.trim().removePrefix("|").removeSuffix("|")
    return content.split("|").all { cell ->
        val c = cell.trim().replace("-", "").replace(":", "").replace(" ", "")
        c.isEmpty()
    }
}
