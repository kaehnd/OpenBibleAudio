package com.openbibleaudio.openbibleaudio.playback

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Chapter-number artwork for Auto's chapter grid (spec 0002 §7.4). */
@RunWith(AndroidJUnit4::class)
class ChapterArtTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun urisRoundTrip() {
        val uri = ChapterArtProvider.uri(context, 23)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.art", uri.authority)
        assertEquals(23, ChapterArtProvider.number(uri))
    }

    @Test
    fun onlyChapterNumbersInRangeAreServed() {
        fun n(s: String) = ChapterArtProvider.number(Uri.parse("content://x.art/$s"))
        assertEquals(150, n("chapter/150"))
        assertNull(n("chapter/0"))
        assertNull(n("chapter/151"))
        assertNull(n("chapter/abc"))
        assertNull(n("chapter/1/extra"))
        assertNull(n("other/1"))
        assertNull(n("chapter/..%2Fdatabases"))
    }

    @Test
    fun chapterTilesAndNowPlayingCarryArtwork() {
        val factory = MediaItemFactory(BookTitles(context)) { ChapterArtProvider.uri(context, it) }
        val id = MediaId.Chapter("bsb", ChapterRef(Book.NUMBERS, 2))
        val tile = factory.browseItem(BrowseNode(id.format(), "2", playable = true, browsable = false, chapter = id))
        assertEquals(2, ChapterArtProvider.number(tile.mediaMetadata.artworkUri!!))

        val folder = factory.browseItem(BrowseNode(MediaId.BIBLE, "Bible", playable = false, browsable = true))
        assertNull(folder.mediaMetadata.artworkUri)
    }
}
