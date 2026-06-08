package com.example.util

import java.util.regex.Pattern

object WordStatsHelper {
    /**
     * Counts the number of words in a given text (supports both Cyrillic and Latin).
     * Accurately matches words with mixed languages, hyphens, and apostrophes.
     * Strips HTML tags before counting.
     */
    fun countWords(htmlOrText: String?): Int {
        if (htmlOrText.isNullOrBlank()) return 0
        // Strip HTML tags cleanly
        val cleanText = htmlOrText.replace(Regex("<[^>]*>"), " ")
        
        // Regex for matching words (Latin & Cyrillic sequences, allowing internal hyphens and apostrophes)
        // [a-zA-Z0-9А-Яа-яЁё]+ matches any alphanumeric sequence in English or Russian
        // (?:[-'][a-zA-Z0-9А-Яа-яЁё]+)* handles composite words like "кто-то", "it's"
        val wordPattern = Pattern.compile("[a-zA-Z0-9А-Яа-яЁё]+(?:[-'][a-zA-Z0-9А-Яа-яЁё]+)*")
        val matcher = wordPattern.matcher(cleanText)
        var count = 0
        while (matcher.find()) {
            count++
        }
        return count
    }

    /**
     * Counts the number of characters in a given text (symbols / glyphs excluding HTML tags and spaces/newlines).
     */
    fun countCharacters(htmlOrText: String?): Int {
        if (htmlOrText.isNullOrBlank()) return 0
        var cleanText = htmlOrText.replace(Regex("<[^>]*>"), "")
        // Remove all whitespaces, tabs, newlines, and non-breaking spaces
        cleanText = cleanText.replace(Regex("[\\s\\u00A0]+"), "")
        return cleanText.length
    }
}
