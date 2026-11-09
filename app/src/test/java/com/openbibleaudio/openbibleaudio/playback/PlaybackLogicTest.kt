package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.Testament
import org.junit.Test
import java.util.TreeSet
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun all(): TreeSet<ChapterRef> = generateSequence(ChapterRef(Book.GENESIS, 1)) { it.next() }.toCollection(TreeSet())

private fun snapshot(vararg versions: Pair<String, Set<ChapterRef>>) = LibrarySnapshot(
    versions = versions.map { VersionInfo(it.first, it.first.uppercase(), "Name ${it.first}") },
    chapters = versions.associate { it.first to TreeSet(it.second) },
)

private object Titles : BrowseTitles {
    override val bible = "Bible"
    override val noLibrary = "No Bible"
    override fun testament(t: Testament) = t.name
    override fun book(b: Book) = b.usfm
    override fun chapterGroup(b: Book, from: Int, to: Int) = "${b.usfm} $from-$to"
    override fun version(v: VersionInfo) = v.abbreviation
}

class QueueBuilderTest {
    @Test
    fun bookScopeStopsAtEndOfBook() {
        val q = QueueBuilder.build(ChapterRef(Book.GENESIS, 48), ContinuousPlayScope.BOOK, all())
        assertEquals(listOf(48, 49, 50), q.map { it.chapter })
    }

    @Test
    fun bibleScopeRunsToTheEndOfTheCanon() {
        val q = QueueBuilder.build(ChapterRef(Book.JUDE, 1), ContinuousPlayScope.BIBLE, all())
        assertEquals(23, q.size)
        assertEquals(ChapterRef(Book.REVELATION, 22), q.last())
    }

    @Test
    fun gapsInThePackAreSkipped() {
        val available = TreeSet(listOf(ChapterRef(Book.GENESIS, 1), ChapterRef(Book.GENESIS, 3), ChapterRef(Book.EXODUS, 1)))
        assertEquals(listOf(ChapterRef(Book.GENESIS, 1), ChapterRef(Book.GENESIS, 3)), QueueBuilder.build(ChapterRef(Book.GENESIS, 1), ContinuousPlayScope.BOOK, available))
        assertEquals(3, QueueBuilder.build(ChapterRef(Book.GENESIS, 1), ContinuousPlayScope.BIBLE, available).size)
        assertEquals(emptyList(), QueueBuilder.build(ChapterRef(Book.GENESIS, 2), ContinuousPlayScope.BOOK, available))
    }

    @Test
    fun lastChapterOfRevelationIsAOneItemQueue() {
        assertEquals(1, QueueBuilder.build(ChapterRef(Book.REVELATION, 22), ContinuousPlayScope.BIBLE, all()).size)
    }

    @Test
    fun firstChapterOfBook() {
        val available = TreeSet(listOf(ChapterRef(Book.ROMANS, 3), ChapterRef(Book.ROMANS, 5)))
        assertEquals(ChapterRef(Book.ROMANS, 3), QueueBuilder.firstChapter(Book.ROMANS, available))
        assertNull(QueueBuilder.firstChapter(Book.JUDE, available))
    }
}

class BrowseTreeTest {
    private val tree = BrowseTree(Titles)

    @Test
    fun emptyLibraryShowsAMessageItem() {
        val children = tree.children(MediaId.Bible, LibrarySnapshot.EMPTY_LIBRARY)!!
        val only = children.single()
        assertEquals(BrowseTree.NO_LIBRARY_ID, only.id)
        assertTrue(!only.playable && !only.browsable)
    }

    @Test
    fun rootHasOnlyBible() {
        assertEquals(listOf("bible"), tree.children(MediaId.Root, snapshot("bsb" to all()))!!.map { it.id })
    }

    @Test
    fun oneVersionShowsTestamentsDirectly() {
        val ids = tree.children(MediaId.Bible, snapshot("bsb" to all()))!!.map { it.id }
        assertEquals(listOf("bible/bsb/OT", "bible/bsb/NT"), ids)
    }

    @Test
    fun severalVersionsShowVersionNodes() {
        val ids = tree.children(MediaId.Bible, snapshot("bsb" to all(), "csb" to all()))!!.map { it.id }
        assertEquals(listOf("bible/bsb", "bible/csb"), ids)
        assertEquals(listOf("bible/csb/OT", "bible/csb/NT"), tree.children(MediaId.Version("csb"), snapshot("bsb" to all(), "csb" to all()))!!.map { it.id })
    }

    @Test
    fun testamentsListAvailableBooksOnly() {
        val snap = snapshot("bsb" to all())
        assertEquals(39, tree.children(MediaId.TestamentNode("bsb", Testament.OLD), snap)!!.size)
        assertEquals(27, tree.children(MediaId.TestamentNode("bsb", Testament.NEW), snap)!!.size)

        val ntOnly = snapshot("nt" to all().filter { it.book.testament == Testament.NEW }.toSet())
        assertEquals(listOf("bible/nt/NT"), tree.children(MediaId.Bible, ntOnly)!!.map { it.id })
        assertNull(tree.node(MediaId.BookNode("nt", Book.GENESIS), ntOnly))
    }

    @Test
    fun genesisIsAGridOf50() {
        val snap = snapshot("bsb" to all())
        val gen = tree.children(MediaId.BookNode("bsb", Book.GENESIS), snap)!!
        assertEquals(50, gen.size)
        assertTrue(gen.all { it.playable })
        assertEquals("1", gen.first().title)
        assertTrue(tree.node(MediaId.BookNode("bsb", Book.GENESIS), snap)!!.gridChildren)
    }

    @Test
    fun psalmsAndIsaiahAreGrouped() {
        val snap = snapshot("bsb" to all())
        assertEquals(
            listOf("bible/bsb/PSA/1-50", "bible/bsb/PSA/51-100", "bible/bsb/PSA/101-150"),
            tree.children(MediaId.BookNode("bsb", Book.PSALMS), snap)!!.map { it.id },
        )
        assertEquals(
            listOf("bible/bsb/ISA/1-50", "bible/bsb/ISA/51-66"),
            tree.children(MediaId.BookNode("bsb", Book.ISAIAH), snap)!!.map { it.id },
        )
        val group = tree.children(MediaId.ChapterGroup("bsb", Book.PSALMS, 101, 150), snap)!!
        assertEquals((101..150).toList(), group.map { it.title.toInt() })
    }

    @Test
    fun everyNodeCanBeRebuiltFromItsId() {
        val snap = snapshot("bsb" to all())
        val ids = listOf(
            MediaId.Root, MediaId.Bible, MediaId.Version("bsb"), MediaId.TestamentNode("bsb", Testament.NEW),
            MediaId.BookNode("bsb", Book.PSALMS), MediaId.ChapterGroup("bsb", Book.PSALMS, 51, 100),
            MediaId.Chapter("bsb", ChapterRef(Book.PSALMS, 23)),
        )
        for (id in ids) assertEquals(id.format(), tree.node(id, snap)!!.id)
        assertEquals("PSA 23", tree.node(MediaId.Chapter("bsb", ChapterRef(Book.PSALMS, 23)), snap)!!.title)
        assertNull(tree.node(MediaId.Chapter("other", ChapterRef(Book.PSALMS, 23)), snap))
        assertNull(tree.children(MediaId.Chapter("bsb", ChapterRef(Book.PSALMS, 23)), snap))
    }
}

class PlaybackRulesTest {
    @Test
    fun resumptionRewindsTwoSecondsClampedAtZero() {
        assertEquals(58_000, ResumptionPolicy.startPosition(60_000))
        assertEquals(0, ResumptionPolicy.startPosition(1_500))
    }

    @Test
    fun completionAt95PercentButNot94() {
        val t = CompletionTracker()
        t.begin("bible/bsb/GEN/1", 100_000)
        t.onPosition(94_000, 100_000)
        assertNull(t.end(naturalEnd = false))

        t.begin("bible/bsb/GEN/1", 100_000)
        t.onPosition(95_000, 100_000)
        assertEquals("bible/bsb/GEN/1", t.end(naturalEnd = false))
        assertNull(t.end(naturalEnd = true), "at most once per play-through")
    }

    @Test
    fun naturalEndAlwaysCounts() {
        val t = CompletionTracker()
        t.begin("bible/bsb/GEN/2", 0)
        assertEquals("bible/bsb/GEN/2", t.end(naturalEnd = true))
    }

    @Test
    fun noItemNoCompletion() {
        val t = CompletionTracker()
        t.begin(null, 0)
        assertNull(t.end(naturalEnd = true))
    }
}

class VoiceSearchTest {
    private val snap = snapshot("bsb" to all(), "nt" to all().filter { it.book.testament == Testament.NEW }.toSet())

    @Test
    fun chapterAndBookQueries() {
        assertEquals(VoiceRequest.Play(MediaId.Chapter("bsb", ChapterRef(Book.PSALMS, 23))), VoiceSearch.resolve("Psalm 23", snap, null))
        assertEquals(VoiceRequest.Play(MediaId.Chapter("bsb", ChapterRef(Book.FIRST_JOHN, 2))), VoiceSearch.resolve("first John chapter two", snap, null))
        assertEquals(VoiceRequest.Play(MediaId.Chapter("bsb", ChapterRef(Book.ROMANS, 1))), VoiceSearch.resolve("Romans", snap, null))
    }

    @Test
    fun preferredVersionIsUsed() {
        assertEquals(VoiceRequest.Play(MediaId.Chapter("nt", ChapterRef(Book.JOHN, 3))), VoiceSearch.resolve("John 3", snap, "nt"))
        assertIs<VoiceRequest.NotFound>(VoiceSearch.resolve("Genesis 1", snap, "nt"))
    }

    @Test
    fun emptyQueryResumes() {
        assertEquals(VoiceRequest.Resume, VoiceSearch.resolve("", snap, null))
    }

    @Test
    fun unknownIsNotFoundWithVersion() {
        assertEquals(VoiceRequest.NotFound("Hezekiah 3", "BSB"), VoiceSearch.resolve("Hezekiah 3", snap, null))
        assertEquals(VoiceRequest.NotFound("Genesis 1", null), VoiceSearch.resolve("Genesis 1", LibrarySnapshot.EMPTY_LIBRARY, null))
    }

    @Test
    fun searchResults() {
        assertEquals(1, VoiceSearch.results("Psalm 23", snap, null).size)
        assertEquals(10, VoiceSearch.results("Psalms", snap, null).size)
        assertEquals(emptyList(), VoiceSearch.results("nonsense", snap, null))
    }
}
