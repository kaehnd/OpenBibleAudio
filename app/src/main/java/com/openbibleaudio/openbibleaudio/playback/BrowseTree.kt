package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.Testament

/** Display strings for the browse tree. The service implements this with string resources. */
interface BrowseTitles {
    val bible: String
    val noLibrary: String
    fun testament(t: Testament): String
    fun book(b: Book): String
    fun chapterGroup(b: Book, from: Int, to: Int): String
    fun version(v: VersionInfo): String
}

/** One node of the browse tree, independent of Media3 types. */
data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val playable: Boolean,
    val browsable: Boolean,
    /** Children should be shown as a grid (chapter numbers). */
    val gridChildren: Boolean = false,
    /** Set for playable chapter nodes. */
    val chapter: MediaId.Chapter? = null,
)

/**
 * The Android Auto browse tree (AD-3, spec 0002 §7.4) as a pure function of a [LibrarySnapshot].
 */
class BrowseTree(private val titles: BrowseTitles) {

    fun root() = BrowseNode(MediaId.ROOT, "Root", playable = false, browsable = true)

    /** Children of [id], or null if [id] doesn't name a browsable node in [snap]. */
    fun children(id: MediaId, snap: LibrarySnapshot): List<BrowseNode>? = when (id) {
        MediaId.Root -> listOf(node(MediaId.Bible, snap)!!)
        MediaId.Bible -> when (snap.versions.size) {
            0 -> listOf(BrowseNode(NO_LIBRARY_ID, titles.noLibrary, playable = false, browsable = false))
            1 -> testaments(snap.versions.single().versionId, snap)
            else -> snap.versions.map { node(MediaId.Version(it.versionId), snap)!! }
        }
        is MediaId.Version -> snap.version(id.versionId)?.let { testaments(it.versionId, snap) }
        is MediaId.TestamentNode -> snap.version(id.versionId)?.let {
            snap.books(id.versionId).filter { it.testament == id.testament }.map { b -> node(MediaId.BookNode(id.versionId, b), snap)!! }
        }
        is MediaId.BookNode -> snap.version(id.versionId)?.let {
            val chapters = snap.chapters(id.versionId, id.book)
            if (chapters.isEmpty()) {
                null
            } else if (chapters.size > CHAPTER_GROUP_SIZE) {
                groups(id.book, chapters).map { (from, to) -> node(MediaId.ChapterGroup(id.versionId, id.book, from, to), snap)!! }
            } else {
                chapters.map { chapterNode(id.versionId, it, short = true) }
            }
        }
        is MediaId.ChapterGroup -> snap.version(id.versionId)?.let {
            snap.chapters(id.versionId, id.book).filter { it.chapter in id.from..id.to }.map { chapterNode(id.versionId, it, short = true) }
                .ifEmpty { null }
        }
        is MediaId.Chapter -> null
    }

    /** The node for [id] itself (Auto's onGetItem after process death), or null if it doesn't exist. */
    fun node(id: MediaId, snap: LibrarySnapshot): BrowseNode? = when (id) {
        MediaId.Root -> root()
        MediaId.Bible -> BrowseNode(id.format(), titles.bible, playable = false, browsable = true)
        is MediaId.Version -> snap.version(id.versionId)?.let {
            BrowseNode(id.format(), titles.version(it), playable = false, browsable = true)
        }
        is MediaId.TestamentNode -> snap.version(id.versionId)
            ?.takeIf { snap.books(id.versionId).any { it.testament == id.testament } }
            ?.let { BrowseNode(id.format(), titles.testament(id.testament), playable = false, browsable = true) }
        is MediaId.BookNode -> snap.version(id.versionId)
            ?.takeIf { snap.chapters(id.versionId, id.book).isNotEmpty() }
            ?.let {
                val grid = snap.chapters(id.versionId, id.book).size <= CHAPTER_GROUP_SIZE
                BrowseNode(id.format(), titles.book(id.book), playable = false, browsable = true, gridChildren = grid)
            }
        is MediaId.ChapterGroup -> snap.version(id.versionId)
            ?.takeIf { snap.chapters(id.versionId, id.book).any { it.chapter in id.from..id.to } }
            ?.let { BrowseNode(id.format(), titles.chapterGroup(id.book, id.from, id.to), playable = false, browsable = true, gridChildren = true) }
        is MediaId.Chapter -> snap.version(id.versionId)
            ?.takeIf { snap.has(id.versionId, id.ref) }
            ?.let { chapterNode(id.versionId, id.ref, short = false) }
    }

    private fun testaments(versionId: String, snap: LibrarySnapshot): List<BrowseNode> =
        Testament.entries.mapNotNull { node(MediaId.TestamentNode(versionId, it), snap) }

    private fun chapterNode(versionId: String, ref: ChapterRef, short: Boolean): BrowseNode {
        val id = MediaId.Chapter(versionId, ref)
        val title = if (short) ref.chapter.toString() else "${titles.book(ref.book)} ${ref.chapter}"
        return BrowseNode(id.format(), title, playable = true, browsable = false, chapter = id)
    }

    companion object {
        /** Books with more chapters than this are split into groups (spec 0002 §7.4, open question 1). */
        const val CHAPTER_GROUP_SIZE = 50

        /** A non-playable, non-browsable message item shown when nothing is imported. */
        const val NO_LIBRARY_ID = "info/no-library"

        /** Groups of [CHAPTER_GROUP_SIZE] chapter numbers that contain at least one available chapter. */
        fun groups(book: Book, chapters: List<ChapterRef>): List<Pair<Int, Int>> =
            (1..book.chapterCount step CHAPTER_GROUP_SIZE)
                .map { from -> from to minOf(from + CHAPTER_GROUP_SIZE - 1, book.chapterCount) }
                .filter { (from, to) -> chapters.any { it.chapter in from..to } }
    }
}
