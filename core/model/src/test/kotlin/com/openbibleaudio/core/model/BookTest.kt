package com.openbibleaudio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BookTest {

    @Test
    fun canonHas66BooksSplit39And27() {
        assertEquals(66, Book.entries.size)
        assertEquals(39, Book.entries.count { it.testament == Testament.OLD })
        assertEquals(27, Book.entries.count { it.testament == Testament.NEW })
    }

    @Test
    fun chapterTotalsMatchEnglishVersification() {
        assertEquals(929, Book.entries.filter { it.testament == Testament.OLD }.sumOf { it.chapterCount })
        assertEquals(260, Book.entries.filter { it.testament == Testament.NEW }.sumOf { it.chapterCount })
    }

    @Test
    fun canonicalOrderStartsAndEndsCorrectly() {
        assertSame(Book.GENESIS, Book.entries.first())
        assertSame(Book.MALACHI, Book.entries.last { it.testament == Testament.OLD })
        assertSame(Book.MATTHEW, Book.entries.first { it.testament == Testament.NEW })
        assertSame(Book.REVELATION, Book.entries.last())
    }

    @Test
    fun testamentsAreContiguous() {
        val firstNew = Book.entries.indexOfFirst { it.testament == Testament.NEW }
        assertTrue(Book.entries.drop(firstNew).all { it.testament == Testament.NEW })
    }

    @Test
    fun usfmCodesAreUniqueThreeCharUppercase() {
        val codes = Book.entries.map { it.usfm }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.length == 3 && it == it.uppercase() })
    }

    @Test
    fun fromUsfmRoundTripsEveryBook() {
        Book.entries.forEach { assertSame(it, Book.fromUsfm(it.usfm)) }
    }

    @Test
    fun fromUsfmIsCaseInsensitiveAndTrims() {
        assertSame(Book.FIRST_JOHN, Book.fromUsfm(" 1jn "))
        assertSame(Book.SONG_OF_SONGS, Book.fromUsfm("sng"))
    }

    @Test
    fun fromUsfmReturnsNullForUnknownCodes() {
        assertNull(Book.fromUsfm("XYZ"))
        assertNull(Book.fromUsfm(""))
        assertNull(Book.fromUsfm("TOB")) // deuterocanon not in this canon (yet)
    }

    @Test
    fun hasChapterRespectsBounds() {
        assertTrue(Book.PSALMS.hasChapter(150))
        assertFalse(Book.PSALMS.hasChapter(151))
        assertFalse(Book.OBADIAH.hasChapter(0))
        assertTrue(Book.OBADIAH.hasChapter(1))
    }
}
