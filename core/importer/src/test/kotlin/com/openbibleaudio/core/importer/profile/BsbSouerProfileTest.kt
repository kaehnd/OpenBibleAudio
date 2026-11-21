package com.openbibleaudio.core.importer.profile

import com.openbibleaudio.core.importer.FileEntry
import com.openbibleaudio.core.importer.fixtures.BsbNames
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.IssueCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BsbSouerProfileTest {
    private val profile = BsbSouerProfile.profile

    private fun match(vararg names: String) = ProfileMatcher.match(profile, names.map { FileEntry(it, 1) })

    @Test
    fun allSouerFileNamesMatchTheWholeCanon() {
        val outcome = ProfileMatcher.match(profile, BsbNames.all(suffix = "").map { FileEntry(it, 1) })
        assertEquals(emptyList(), outcome.issues)
        assertEquals(1189, outcome.matches.size)
        val expected = Book.entries.flatMap { b -> (1..b.chapterCount).map { b to it } }.toSet()
        assertEquals(expected, outcome.matches.map { it.book to it.chapter }.toSet())
    }

    @Test
    fun realisticNames() {
        val m = match("BSB_01_Gen_001.mp3", "BSB_19_Psa_119.mp3", "BSB_62_1Jn_005.mp3", "BSB_56_Tts_001.mp3").matches
        assertEquals(
            listOf(Book.GENESIS to 1, Book.PSALMS to 119, Book.FIRST_JOHN to 5, Book.TITUS to 1),
            m.map { it.book to it.chapter },
        )
    }

    @Test
    fun orderMustMatchCanon() {
        assertEquals(IssueCode.PROFILE_CHECK_FAILED, match("BSB_02_Gen_001.mp3").issues.single().code)
    }

    @Test
    fun theTestamentZipsAndGilbertNamesAreUnmatched() {
        val outcome = match("BSB_00_Souer_OT.zip", "BSB_00_Souer_NT.zip", "BSB_01_Gen_001_G.mp3")
        assertEquals(3, outcome.unmatched.size)
        assertTrue(outcome.issues.all { it.code == IssueCode.UNMATCHED_FILE })
    }

    @Test
    fun gilbertDoesNotMatchSouerNames() {
        assertEquals(1, ProfileMatcher.match(BsbGilbertProfile.profile, listOf(FileEntry("BSB_01_Gen_001.mp3", 1))).unmatched.size)
    }

    @Test
    fun registry() {
        assertEquals(profile, LayoutProfiles.byId("bsb-souer"))
        assertEquals("bsb-souer", profile.bibleDefaults?.versionId)
    }
}
