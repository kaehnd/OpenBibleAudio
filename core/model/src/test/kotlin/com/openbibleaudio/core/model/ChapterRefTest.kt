package com.openbibleaudio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterRefTest {
    @Test
    fun validatesRange() {
        assertFailsWith<IllegalArgumentException> { ChapterRef(Book.GENESIS, 51) }
        assertFailsWith<IllegalArgumentException> { ChapterRef(Book.GENESIS, 0) }
        assertNull(ChapterRef.of(Book.JUDE, 2))
    }

    @Test
    fun nextAndPreviousCrossBookAndCanonBoundaries() {
        assertEquals(ChapterRef(Book.GENESIS, 2), ChapterRef(Book.GENESIS, 1).next())
        assertEquals(ChapterRef(Book.EXODUS, 1), ChapterRef(Book.GENESIS, 50).next())
        assertEquals(ChapterRef(Book.MATTHEW, 1), ChapterRef(Book.MALACHI, 4).next())
        assertNull(ChapterRef(Book.REVELATION, 22).next())
        assertEquals(ChapterRef(Book.GENESIS, 50), ChapterRef(Book.EXODUS, 1).previous())
        assertNull(ChapterRef(Book.GENESIS, 1).previous())
    }

    @Test
    fun walkingTheCanonVisitsEveryChapterInOrder() {
        val all = generateSequence(ChapterRef(Book.GENESIS, 1)) { it.next() }.toList()
        assertEquals(1189, all.size)
        assertTrue(all.zipWithNext().all { (a, b) -> a < b })
    }
}
