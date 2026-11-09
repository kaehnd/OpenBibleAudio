package com.openbibleaudio.core.model.plan

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReadingListParserTest {
    private fun parse(s: String) = ReadingListParser.parse(s)

    @Test
    fun singleRange() {
        assertEquals(listOf(Reading(Book.GENESIS, 1, 3)), parse("Genesis 1-3"))
        assertEquals(listOf(Reading(Book.NUMBERS, 7)), parse("Numbers 7"))
    }

    @Test
    fun bareNumbersCarryTheBookOver() {
        assertEquals(
            listOf(27, 31, 34, 52).let { rest -> listOf(Reading(Book.PSALMS, 7)) + rest.map { Reading(Book.PSALMS, it) } },
            parse("Psalm 7, 27, 31, 34, 52"),
        )
        assertEquals(
            listOf(Reading(Book.PSALMS, 56), Reading(Book.PSALMS, 120), Reading(Book.PSALMS, 140, 142)),
            parse("Psalm 56, 120, 140 - 142"),
        )
    }

    @Test
    fun mixedBooks() {
        assertEquals(
            listOf(Reading(Book.FIRST_SAMUEL, 18, 20), Reading(Book.PSALMS, 11), Reading(Book.PSALMS, 59)),
            parse("1 Samuel 18-20, Psalm 11, Psalm 59"),
        )
        assertEquals(
            listOf(Reading(Book.SECOND_JOHN, 1), Reading(Book.THIRD_JOHN, 1)),
            parse("2 John 1, 3 John 1"),
        )
        assertEquals(listOf(Reading(Book.SONG_OF_SONGS, 1, 8)), parse("Song of Solomon 1-8"))
        assertEquals(listOf(Reading(Book.OBADIAH, 1), Reading(Book.PSALMS, 82, 83)), parse("Obadiah 1, Psalm 82-83"))
    }

    @Test
    fun pdfShortForms() {
        assertEquals(listOf(Reading(Book.FIRST_SAMUEL, 18, 20), Reading(Book.PSALMS, 11), Reading(Book.PSALMS, 59)), parse("1 Sam 18-20\nPs 11, 59"))
        assertEquals(listOf(Reading(Book.FIRST_CHRONICLES, 1, 2)), parse("1 Ch 1-2"))
        assertEquals(listOf(Reading(Book.JUDGES, 1, 2)), parse("Jdg 1-2"))
        assertEquals(listOf(Reading(Book.PHILIPPIANS, 1, 4)), parse("Php 1-4"))
        assertEquals(listOf(Reading(Book.COLOSSIANS, 1, 4), Reading(Book.PHILEMON, 1)), parse("Col 1-4; Phm 1"))
        assertEquals(listOf(Reading(Book.FIRST_THESSALONIANS, 1, 5), Reading(Book.SECOND_THESSALONIANS, 1, 3)), parse("1 Th 1-5, 2 Th 1-3"))
        assertEquals(listOf(Reading(Book.JAMES, 1, 5)), parse("Jas 1–5"))
        assertEquals(listOf(Reading(Book.DEUTERONOMY, 1, 2)), parse("Deut. 1-2"))
    }

    @Test
    fun singleChapterRangeCollapses() {
        assertEquals(listOf(Reading(Book.MATTHEW, 2)), parse("Matthew 2-2"))
    }

    @Test
    fun rejectsAnythingItCannotParseFully() {
        assertNull(parse(""))
        assertNull(parse("27, Psalm 3")) // no book for the first segment
        assertNull(parse("Hezekiah 3"))
        assertNull(parse("Genesis 51"))
        assertNull(parse("Genesis 5-3"))
        assertNull(parse("Genesis"))
        assertNull(parse("Genesis 1:1-3"))
        assertNull(parse("Day 276 The Bible KNEEcap Preview"))
    }

    /** Every reading list in a year of TBR titles parses, and together they read the Bible once. */
    @Test
    fun allBibleRecapTitlesParseAndCoverTheCanonOnce() {
        val lines = javaClass.getResource("/plan/tbr-readings.txt")!!.readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(365, lines.size)
        val chapters = mutableListOf<ChapterRef>()
        lines.forEachIndexed { i, line ->
            val (day, text) = line.split('\t')
            assertEquals(i + 1, day.toInt())
            val readings = assertNotNull(parse(text), "day $day: '$text'")
            chapters += readings.flatMap { it.chapters() }
        }
        assertEquals(1189, chapters.size)
        assertEquals(1189, chapters.toSet().size)
    }
}
