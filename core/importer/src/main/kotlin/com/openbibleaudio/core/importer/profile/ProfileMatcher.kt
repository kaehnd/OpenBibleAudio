package com.openbibleaudio.core.importer.profile

import com.openbibleaudio.core.importer.FileEntry
import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.IssueCode

data class MatchOutcome(
    val matches: List<ChapterMatch>,
    /** Files that produced at least one match. */
    val matchedFiles: List<FileEntry>,
    val unmatched: List<FileEntry>,
    val issues: List<Issue>,
)

/** Applies a [LayoutProfile] to a list of files (spec 0001 §6.3). */
object ProfileMatcher {
    fun match(profile: LayoutProfile, entries: List<FileEntry>): MatchOutcome {
        val matches = mutableListOf<ChapterMatch>()
        val matchedFiles = mutableListOf<FileEntry>()
        val unmatched = mutableListOf<FileEntry>()
        val issues = mutableListOf<Issue>()

        for (entry in entries) {
            val m = profile.pathPattern.matchEntire(entry.path)
            if (m == null) {
                unmatched += entry
                issues += Issue(IssueCode.UNMATCHED_FILE, "doesn't match profile '${profile.id}'; not included", entry.path)
                continue
            }
            val custom = profile.custom
            val fileMatches = if (custom != null) custom(entry, m) else listOfNotNull(defaultMatch(profile, entry, m, issues))
            if (fileMatches.isNotEmpty()) matchedFiles += entry
            matches += fileMatches
        }
        return MatchOutcome(matches, matchedFiles, unmatched, issues)
    }

    private fun defaultMatch(profile: LayoutProfile, entry: FileEntry, m: MatchResult, issues: MutableList<Issue>): ChapterMatch? {
        val alias = m.groups["book"]?.value
            ?: error("Profile '${profile.id}' pathPattern has no 'book' group")
        val chapterText = m.groups["chapter"]?.value
            ?: error("Profile '${profile.id}' pathPattern has no 'chapter' group")

        val book = profile.books.resolve(alias)
        if (book == null) {
            issues += Issue(IssueCode.UNKNOWN_BOOK_ALIAS, "book '$alias' isn't known to profile '${profile.id}'", entry.path)
            return null
        }
        val chapter = chapterText.toIntOrNull()
        if (chapter == null || !book.hasChapter(chapter)) {
            issues += Issue(IssueCode.NO_SUCH_CHAPTER, "${book.usfm} has no chapter '$chapterText'", entry.path)
            return null
        }
        val failures = profile.checks.mapNotNull { it.check(m, book, entry.path) }
        if (failures.isNotEmpty()) {
            failures.forEach { issues += Issue(IssueCode.PROFILE_CHECK_FAILED, it, entry.path) }
            return null
        }
        return ChapterMatch(book, chapter, entry.path)
    }
}
