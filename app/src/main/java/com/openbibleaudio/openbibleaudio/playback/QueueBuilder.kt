package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import java.util.SortedSet

/** Continuous-play queues (AD-2, spec 0002 §7.3). Pure. */
object QueueBuilder {
    /**
     * Chapters from [start] to the end of its book ([ContinuousPlayScope.BOOK]) or the canon
     * ([ContinuousPlayScope.BIBLE]), skipping chapters the pack doesn't have. Empty if [start]
     * itself isn't available.
     */
    fun build(start: ChapterRef, scope: ContinuousPlayScope, available: SortedSet<ChapterRef>): List<ChapterRef> {
        if (start !in available) return emptyList()
        val tail = available.tailSet(start)
        return when (scope) {
            ContinuousPlayScope.BOOK -> tail.takeWhile { it.book == start.book }
            ContinuousPlayScope.BIBLE -> tail.toList()
        }
    }

    /** The first available chapter of [book], for "play Romans". */
    fun firstChapter(book: Book, available: SortedSet<ChapterRef>): ChapterRef? = available.firstOrNull { it.book == book }
}
