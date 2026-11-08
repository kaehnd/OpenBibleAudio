package com.openbibleaudio.core.model.pack

import com.openbibleaudio.core.model.Book
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestValidatorTest {

    private val bible = PackBible("bsb-gilbert", "BSB", "Berean Standard Bible", "en", "eng")

    /** One file per chapter, all with durations: a complete, valid pack. */
    private fun complete(): PackManifest {
        val chapters = Book.entries.flatMap { b -> (1..b.chapterCount).map { PackChapter(b, it, "${b.usfm}/$it.mp3") } }
        val files = chapters.map { PackFile(it.file, 1000, durationMs = 60_000) }
        return PackManifest(1, bible, null, files, chapters)
    }

    private fun small(
        files: List<PackFile> = listOf(PackFile("a.mp3", 10, durationMs = 1000)),
        chapters: List<PackChapter> = listOf(PackChapter(Book.JUDE, 1, "a.mp3")),
        bible: PackBible = this.bible,
        formatVersion: Int = 1,
    ) = PackManifest(formatVersion, bible, null, files, chapters)

    private fun codes(m: PackManifest) = ManifestValidator.validate(m).issues.map { it.code }.toSet()

    private fun assertHas(code: IssueCode, m: PackManifest) {
        val issues = ManifestValidator.validate(m).issues
        assertTrue(issues.any { it.code == code }, "expected $code in $issues")
    }

    @Test
    fun completePackHasNoIssues() {
        assertEquals(emptyList(), ManifestValidator.validate(complete()).issues)
    }

    @Test
    fun bibleFieldRules() {
        assertHas(IssueCode.UNSUPPORTED_FORMAT, small(formatVersion = 2))
        assertHas(IssueCode.BAD_VERSION_ID, small(bible = bible.copy(versionId = "BSB")))
        assertHas(IssueCode.BAD_VERSION_ID, small(bible = bible.copy(versionId = "-bsb")))
        assertHas(IssueCode.BAD_VERSION_ID, small(bible = bible.copy(versionId = "a".repeat(33))))
        assertHas(IssueCode.BAD_LANGUAGE, small(bible = bible.copy(language = "english language")))
        assertHas(IssueCode.BAD_FIELD_LENGTH, small(bible = bible.copy(abbreviation = "")))
        assertHas(IssueCode.BAD_FIELD_LENGTH, small(bible = bible.copy(name = "n".repeat(101))))
        assertHas(IssueCode.BAD_FIELD_LENGTH, small(bible = bible.copy(attribution = "a".repeat(2001))))
        assertHas(IssueCode.UNSUPPORTED_VERSIFICATION, small(bible = bible.copy(versification = "lxx")))
        assertTrue(ManifestValidator.validate(small(bible = bible.copy(language = "en-US"))).issues.none { it.code == IssueCode.BAD_LANGUAGE })
    }

    @Test
    fun emptyPack() {
        assertHas(IssueCode.EMPTY_PACK, small(files = emptyList(), chapters = emptyList()))
    }

    @Test
    fun pathRules() {
        for (bad in listOf("", "/abs.mp3", "a\\b.mp3", "a/../b.mp3", "./a.mp3", "a//b.mp3", "a/", "tab\t.mp3", "é.mp3")) {
            assertTrue(ManifestValidator.pathProblem(bad) != null, "expected '$bad' to be rejected")
        }
        for (good in listOf("a.mp3", "01 Genesis/Genesis 001.mp3", "é.mp3", "a..b.mp3")) {
            assertNull(ManifestValidator.pathProblem(good), "expected '$good' to be accepted")
        }
        assertHas(IssueCode.BAD_PATH, small(files = listOf(PackFile("../a.mp3", 1)), chapters = listOf(PackChapter(Book.JUDE, 1, "../a.mp3"))))
    }

    @Test
    fun duplicatePathsCompareCaseInsensitively() {
        val m = small(files = listOf(PackFile("a.mp3", 10), PackFile("A.MP3", 10)))
        assertHas(IssueCode.DUPLICATE_PATH, m)
    }

    @Test
    fun fileFieldRules() {
        assertHas(IssueCode.BAD_SIZE, small(files = listOf(PackFile("a.mp3", 0))))
        assertHas(IssueCode.BAD_HASH, small(files = listOf(PackFile("a.mp3", 1, sha256 = "ABC"))))
        assertHas(IssueCode.BAD_HASH, small(files = listOf(PackFile("a.mp3", 1, sha256 = "A".repeat(64)))))
        assertHas(IssueCode.BAD_DURATION, small(files = listOf(PackFile("a.mp3", 1, durationMs = 0))))
    }

    @Test
    fun chapterRules() {
        assertHas(IssueCode.NO_SUCH_CHAPTER, small(chapters = listOf(PackChapter(Book.JUDE, 2, "a.mp3"))))
        assertHas(IssueCode.NO_SUCH_CHAPTER, small(chapters = listOf(PackChapter(Book.JUDE, 0, "a.mp3"))))
        assertHas(IssueCode.DUPLICATE_CHAPTER, small(chapters = listOf(PackChapter(Book.JUDE, 1, "a.mp3", endMs = 10), PackChapter(Book.JUDE, 1, "a.mp3", startMs = 10))))
        assertHas(IssueCode.UNKNOWN_FILE, small(chapters = listOf(PackChapter(Book.JUDE, 1, "A.mp3"))))
        assertHas(IssueCode.BAD_SPAN, small(chapters = listOf(PackChapter(Book.JUDE, 1, "a.mp3", startMs = -1))))
        assertHas(IssueCode.BAD_SPAN, small(chapters = listOf(PackChapter(Book.JUDE, 1, "a.mp3", startMs = 500, endMs = 500))))
        assertHas(IssueCode.SPAN_EXCEEDS_FILE, small(chapters = listOf(PackChapter(Book.JUDE, 1, "a.mp3", endMs = 1001))))
        assertHas(IssueCode.SPAN_EXCEEDS_FILE, small(chapters = listOf(PackChapter(Book.JUDE, 1, "a.mp3", startMs = 1000))))
    }

    @Test
    fun bookPerFileSpans() {
        val file = PackFile("jn.mp3", 10, durationMs = 3000)
        val ok = small(
            files = listOf(file),
            chapters = listOf(
                PackChapter(Book.SECOND_JOHN, 1, "jn.mp3", 0, 1000),
                PackChapter(Book.THIRD_JOHN, 1, "jn.mp3", 1000, null),
            ),
        )
        assertTrue(codes(ok).none { it.severity == Severity.ERROR }, "${ManifestValidator.validate(ok)}")

        val overlapping = ok.copy(chapters = listOf(PackChapter(Book.SECOND_JOHN, 1, "jn.mp3", 0, null), PackChapter(Book.THIRD_JOHN, 1, "jn.mp3", 1000, null)))
        assertHas(IssueCode.OVERLAPPING_SPANS, overlapping)
    }

    @Test
    fun unreferencedFileAndMissingDurationAreWarnings() {
        val m = small(files = listOf(PackFile("a.mp3", 10), PackFile("extra.mp3", 10, durationMs = 5)))
        val issues = ManifestValidator.validate(m).issues
        assertEquals(Severity.WARNING, issues.single { it.code == IssueCode.UNREFERENCED_FILE }.severity)
        assertEquals(Severity.WARNING, issues.single { it.code == IssueCode.NO_DURATION }.severity)
        assertTrue(!ManifestValidator.validate(m).hasErrors)
    }

    @Test
    fun missingChaptersAreSummarisedAsInfo() {
        val full = complete()
        val drop = setOf(Book.PSALMS to 23, Book.JOHN to 1, Book.JOHN to 2, Book.JOHN to 3, Book.JOHN to 5, Book.JUDE to 1)
        val chapters = full.chapters.filterNot { (it.book to it.chapter) in drop }
        val m = full.copy(chapters = chapters, files = full.files.filter { f -> chapters.any { it.file == f.path } })
        val issue = ManifestValidator.validate(m).issues.single()
        assertEquals(IssueCode.MISSING_CHAPTERS, issue.code)
        assertEquals(Severity.INFO, issue.severity)
        assertEquals("missing: PSA 23; JHN 1-3, 5; all of JUD", issue.message)
    }

    @Test
    fun missingChaptersSummaryIsNullWhenComplete() {
        assertNull(MissingChapters.summarize(complete().chapters.map { it.book to it.chapter }.toSet()))
    }
}
