package com.openbibleaudio.core.importer.profile

import com.openbibleaudio.core.importer.FileEntry
import com.openbibleaudio.core.importer.fixtures.BsbNames
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.IssueCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BsbGilbertProfileTest {
    private val profile = BsbGilbertProfile.profile

    private fun match(vararg names: String) = ProfileMatcher.match(profile, names.map { FileEntry(it, 1) })

    @Test
    fun allBsbFileNamesMatchTheWholeCanon() {
        val outcome = ProfileMatcher.match(profile, BsbNames.all().map { FileEntry(it, 1) })
        assertEquals(emptyList(), outcome.issues)
        assertEquals(1189, outcome.matches.size)
        val expected = Book.entries.flatMap { b -> (1..b.chapterCount).map { b to it } }.toSet()
        assertEquals(expected, outcome.matches.map { it.book to it.chapter }.toSet())
    }

    @Test
    fun realisticNames() {
        val m = match("BSB_01_Gen_001_G.mp3", "BSB_19_Psa_119_G.mp3", "BSB_62_1Jn_005_G.mp3", "BSB_56_Tts_001_G.mp3", "BSB_22_Sng_008_G.mp3").matches
        assertEquals(
            listOf(Book.GENESIS to 1, Book.PSALMS to 119, Book.FIRST_JOHN to 5, Book.TITUS to 1, Book.SONG_OF_SONGS to 8),
            m.map { it.book to it.chapter },
        )
    }

    @Test
    fun orderMustMatchCanon() {
        val outcome = match("BSB_02_Gen_001_G.mp3")
        assertEquals(IssueCode.PROFILE_CHECK_FAILED, outcome.issues.single().code)
        assertTrue(outcome.matches.isEmpty())
    }

    @Test
    fun otherFilesAreUnmatchedWarnings() {
        val outcome = match("notes.txt", "BSB_01_Gen_001_G (1).mp3", "sub/BSB_01_Gen_001_G.mp3")
        assertEquals(3, outcome.unmatched.size)
        assertTrue(outcome.issues.all { it.code == IssueCode.UNMATCHED_FILE })
    }

    @Test
    fun unknownAliasAndOutOfRangeChapterAreErrors() {
        assertEquals(IssueCode.UNKNOWN_BOOK_ALIAS, match("BSB_01_Gnz_001_G.mp3").issues.single().code)
        // The USFM code itself works too, not only the Tts alias.
        assertEquals(Book.TITUS, match("BSB_56_Tit_001_G.mp3").matches.single().book)
        assertEquals(IssueCode.NO_SUCH_CHAPTER, match("BSB_65_Jud_002_G.mp3").issues.single().code)
        assertEquals(IssueCode.NO_SUCH_CHAPTER, match("BSB_01_Gen_000_G.mp3").issues.single().code)
    }

    @Test
    fun aliasesAreCaseInsensitive() {
        val aliases = BookAliases.usfm() + mapOf("tts" to Book.TITUS)
        assertEquals(Book.TITUS, aliases.resolve("TtS"))
        assertEquals(Book.FIRST_JOHN, aliases.resolve("1jn"))
    }

    @Test
    fun registry() {
        assertEquals(profile, LayoutProfiles.byId("bsb-gilbert"))
        assertEquals(null, LayoutProfiles.byId("csb"))
    }
}
