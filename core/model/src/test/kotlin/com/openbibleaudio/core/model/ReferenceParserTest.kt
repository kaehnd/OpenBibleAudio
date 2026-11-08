package com.openbibleaudio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReferenceParserTest {
    private fun ref(book: Book, chapter: Int? = null, verse: Int? = null) = ParsedReference(book, chapter, verse)

    @Test
    fun specExamples() {
        assertEquals(ref(Book.GENESIS, 3), ReferenceParser.parse("Gen 3"))
        assertEquals(ref(Book.GENESIS, 3), ReferenceParser.parse("genesis chapter three"))
        assertEquals(ref(Book.FIRST_JOHN, 2), ReferenceParser.parse("1 John 2"))
        assertEquals(ref(Book.FIRST_JOHN, 2), ReferenceParser.parse("First John 2"))
        assertEquals(ref(Book.SONG_OF_SONGS, 4, 2), ReferenceParser.parse("Song of Songs 4:2"))
        assertEquals(ref(Book.PSALMS, 23), ReferenceParser.parse("Psalm 23"))
        assertEquals(ref(Book.PSALMS, 119), ReferenceParser.parse("ps 119"))
        assertEquals(ref(Book.ROMANS), ReferenceParser.parse("Romans"))
        assertEquals(ref(Book.JUDE), ReferenceParser.parse("Jude"))
    }

    @Test
    fun rejections() {
        assertNull(ReferenceParser.parse("Hezekiah 3"))
        assertNull(ReferenceParser.parse(""))
        assertNull(ReferenceParser.parse("   "))
        assertNull(ReferenceParser.parse("Genesis 51"))
        assertNull(ReferenceParser.parse("Genesis 0"))
        assertNull(ReferenceParser.parse("Genesis three apples"))
        assertNull(ReferenceParser.parse("Genesis 1 2 3"))
    }

    @Test
    fun numberWords() {
        assertEquals(ref(Book.PSALMS, 119), ReferenceParser.parse("psalm one hundred nineteen"))
        assertEquals(ref(Book.PSALMS, 119), ReferenceParser.parse("psalm one hundred and nineteen"))
        assertEquals(ref(Book.PSALMS, 100), ReferenceParser.parse("psalm a hundred"))
        assertEquals(ref(Book.PSALMS, 150), ReferenceParser.parse("psalm one hundred fifty"))
        assertEquals(ref(Book.ISAIAH, 53), ReferenceParser.parse("isaiah fifty three"))
        assertEquals(ref(Book.JOHN, 3, 16), ReferenceParser.parse("john chapter three verse sixteen"))
        assertEquals(ref(Book.PROVERBS, 31), ReferenceParser.parse("proverbs thirty one"))
        assertEquals(ref(Book.GENESIS, 12), ReferenceParser.parse("genesis twelve"))
    }

    @Test
    fun ordinalsAndNumberedBooks() {
        assertEquals(ref(Book.SECOND_KINGS, 5), ReferenceParser.parse("II Kings 5"))
        assertEquals(ref(Book.SECOND_KINGS, 5), ReferenceParser.parse("2nd kings 5"))
        assertEquals(ref(Book.THIRD_JOHN, 1), ReferenceParser.parse("third john 1"))
        assertEquals(ref(Book.FIRST_SAMUEL, 17), ReferenceParser.parse("1Sam 17"))
        assertEquals(ref(Book.FIRST_CORINTHIANS, 13), ReferenceParser.parse("first corinthians thirteen"))
        assertEquals(ref(Book.FIRST_JOHN, 4), ReferenceParser.parse("1JN 4"))
        assertEquals(ref(Book.JOHN, 1), ReferenceParser.parse("John 1"))
        assertEquals(ref(Book.FIRST_JOHN, 1), ReferenceParser.parse("1-John 1"))
    }

    @Test
    fun fillersAndPunctuation() {
        assertEquals(ref(Book.GENESIS, 1), ReferenceParser.parse("play the book of Genesis 1"))
        assertEquals(ref(Book.REVELATION, 22), ReferenceParser.parse("The Revelation 22"))
        assertEquals(ref(Book.REVELATION, 22), ReferenceParser.parse("Revelations 22."))
        assertEquals(ref(Book.SONG_OF_SONGS, 1), ReferenceParser.parse("Song of Solomon 1"))
        assertEquals(ref(Book.ACTS, 2), ReferenceParser.parse("acts of the apostles 2"))
    }

    @Test
    fun everyFullNameAndUsfmCodeParses() {
        for (b in Book.entries) {
            assertEquals(ref(b, 1), ReferenceParser.parse("${b.usfm} 1"), b.usfm)
        }
    }
}
