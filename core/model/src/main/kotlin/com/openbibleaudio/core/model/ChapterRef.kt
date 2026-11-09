package com.openbibleaudio.core.model

/** One chapter of the canon (spec 0002 §2.1). Ordered canonically. */
data class ChapterRef(val book: Book, val chapter: Int) : Comparable<ChapterRef> {
    init {
        require(book.hasChapter(chapter)) { "${book.usfm} has no chapter $chapter" }
    }

    override fun compareTo(other: ChapterRef): Int =
        compareValuesBy(this, other, { it.book.ordinal }, { it.chapter })

    /** The next chapter in canonical order, or null after Revelation 22. */
    fun next(): ChapterRef? = when {
        chapter < book.chapterCount -> ChapterRef(book, chapter + 1)
        book.ordinal + 1 < Book.entries.size -> ChapterRef(Book.entries[book.ordinal + 1], 1)
        else -> null
    }

    /** The previous chapter in canonical order, or null before Genesis 1. */
    fun previous(): ChapterRef? = when {
        chapter > 1 -> ChapterRef(book, chapter - 1)
        book.ordinal > 0 -> Book.entries[book.ordinal - 1].let { ChapterRef(it, it.chapterCount) }
        else -> null
    }

    override fun toString(): String = "${book.usfm} $chapter"

    companion object {
        /** Null instead of throwing when [chapter] is out of range. */
        fun of(book: Book, chapter: Int): ChapterRef? = if (book.hasChapter(chapter)) ChapterRef(book, chapter) else null
    }
}
