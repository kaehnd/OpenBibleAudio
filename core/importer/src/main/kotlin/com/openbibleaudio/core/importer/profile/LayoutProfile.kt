package com.openbibleaudio.core.importer.profile

import com.openbibleaudio.core.importer.FileEntry
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.PackBible

/** One file (or span of a file) mapped to a chapter by a [LayoutProfile]. */
data class ChapterMatch(
    val book: Book,
    val chapter: Int,
    val path: String,
    val startMs: Long = 0,
    val endMs: Long? = null,
)

/**
 * Describes how a vendor's file layout maps to book/chapter (AD-5, spec 0001 §6.3).
 *
 * [pathPattern] is matched against the whole relative path and must define the named groups
 * `book` and `chapter` (and may define `order`). [custom], if set, replaces the default
 * one-file-one-chapter mapping for files that match.
 */
data class LayoutProfile(
    val id: String,
    val description: String,
    val pathPattern: Regex,
    val books: BookAliases,
    val bibleDefaults: PackBible?,
    val checks: List<ProfileCheck> = emptyList(),
    val custom: ((FileEntry, MatchResult) -> List<ChapterMatch>)? = null,
)

/** Case-insensitive alias → [Book] map. Always contains every USFM code. */
class BookAliases private constructor(private val map: Map<String, Book>) {
    fun resolve(alias: String): Book? = map[alias.trim().uppercase()]

    operator fun plus(extra: Map<String, Book>): BookAliases =
        BookAliases(map + extra.mapKeys { it.key.trim().uppercase() })

    companion object {
        fun usfm(): BookAliases = BookAliases(Book.entries.associateBy { it.usfm })
    }
}

/** A per-match check. Returns null if it passes, otherwise an error message. */
fun interface ProfileCheck {
    fun check(match: MatchResult, book: Book, path: String): String?

    companion object {
        /** The [group] (e.g. `order` = "01") must equal the book's canonical position, 1-based. */
        fun orderMatchesCanon(group: String) = ProfileCheck { match, book, _ ->
            val order = match.groups[group]?.value?.toIntOrNull()
            val expected = book.ordinal + 1
            if (order == expected) null else "$group '${match.groups[group]?.value}' doesn't match ${book.usfm} (expected $expected)"
        }
    }
}

/** The built-in profiles (spec 0001 §6.3). */
object LayoutProfiles {
    val all: List<LayoutProfile> = listOf(BsbGilbertProfile.profile)

    fun byId(id: String): LayoutProfile? = all.firstOrNull { it.id == id }
}
