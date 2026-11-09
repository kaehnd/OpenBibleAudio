package com.openbibleaudio.core.model.plan

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.BookNames

/**
 * Parses a reading list such as `"1 Samuel 18-20, Psalm 11, Psalm 59"` or
 * `"Psalm 7, 27, 31, 34, 52"` into [Reading]s (spec 0003 §3.3).
 *
 * - Segments are separated by commas, semicolons or newlines.
 * - A segment that starts with a book name sets the current book. A bare number or range reuses
 *   the previous segment's book.
 * - Ranges use `-`, an en dash or an em dash, with or without spaces.
 * - Book names are anything [BookNames] knows: full names and short forms ("1 Sam", "Ps", "Php").
 *
 * Returns null unless the whole text parses into valid chapters. It never guesses.
 */
object ReadingListParser {
    private val SEGMENT = Regex("^(.*?)\\s*([0-9]{1,3})(?:\\s*[-–—]\\s*([0-9]{1,3}))?$")
    private val SEPARATORS = Regex("[,;\\n]")

    fun parse(text: String): List<Reading>? {
        val segments = text.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var book: Book? = null
        val out = mutableListOf<Reading>()
        for (segment in segments) {
            val m = SEGMENT.matchEntire(segment) ?: return null
            val name = m.groupValues[1]
            if (name.isNotEmpty()) book = lookupBook(name) ?: return null
            val b = book ?: return null
            val from = m.groupValues[2].toInt()
            val to = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            if (!b.hasChapter(from) || (to != null && (!b.hasChapter(to) || to < from))) return null
            out += Reading(b, from, to?.takeIf { it != from })
        }
        return out
    }

    private fun lookupBook(name: String): Book? {
        val phrase = name.lowercase().replace(".", " ").trim().split(Regex("\\s+")).joinToString(" ")
        return BookNames.lookup(phrase)
    }
}
