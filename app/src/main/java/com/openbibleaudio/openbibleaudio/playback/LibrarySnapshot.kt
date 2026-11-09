package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import java.util.SortedSet

/** A READY Bible version as the player sees it. */
data class VersionInfo(val versionId: String, val abbreviation: String, val name: String)

/**
 * Immutable view of what's playable: READY versions and their available chapters. The browse
 * tree, queues and voice search are pure functions of this, so they're testable on the JVM.
 */
data class LibrarySnapshot(
    val versions: List<VersionInfo>,
    val chapters: Map<String, SortedSet<ChapterRef>>,
) {
    fun version(versionId: String): VersionInfo? = versions.firstOrNull { it.versionId == versionId }

    fun chapters(versionId: String): SortedSet<ChapterRef> = chapters[versionId] ?: EMPTY

    fun has(versionId: String, ref: ChapterRef): Boolean = ref in chapters(versionId)

    fun books(versionId: String): List<Book> = chapters(versionId).map { it.book }.distinct()

    fun chapters(versionId: String, book: Book): List<ChapterRef> = chapters(versionId).filter { it.book == book }

    companion object {
        private val EMPTY: SortedSet<ChapterRef> = sortedSetOf()
        val EMPTY_LIBRARY = LibrarySnapshot(emptyList(), emptyMap())
    }
}

/** Where continuous play stops (spec 0002 §5.2, §7.3). */
enum class ContinuousPlayScope { BOOK, BIBLE }
