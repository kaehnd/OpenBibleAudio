package com.openbibleaudio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MediaIdTest {
    private val gen1 = ChapterRef(Book.GENESIS, 1)

    @Test
    fun everyVariantRoundTrips() {
        val ids = listOf(
            MediaId.Root,
            MediaId.Bible,
            MediaId.Version("bsb-gilbert"),
            MediaId.TestamentNode("bsb-gilbert", Testament.OLD),
            MediaId.TestamentNode("bsb-gilbert", Testament.NEW),
            MediaId.BookNode("bsb-gilbert", Book.FIRST_JOHN),
            MediaId.ChapterGroup("bsb-gilbert", Book.PSALMS, 51, 100),
            MediaId.Chapter("bsb-gilbert", gen1),
            MediaId.Chapter("csb", ChapterRef(Book.PSALMS, 150)),
        )
        for (id in ids) {
            val s = id.format()
            assertEquals(id, MediaId.parse(s), s)
            assertEquals(s, MediaId.parse(s)!!.format())
        }
    }

    @Test
    fun formats() {
        assertEquals("root", MediaId.Root.format())
        assertEquals("bible/bsb-gilbert/OT", MediaId.TestamentNode("bsb-gilbert", Testament.OLD).format())
        assertEquals("bible/bsb-gilbert/GEN/1", MediaId.Chapter("bsb-gilbert", gen1).format())
        assertEquals("bible/bsb-gilbert/PSA/1-50", MediaId.ChapterGroup("bsb-gilbert", Book.PSALMS, 1, 50).format())
    }

    @Test
    fun rejectsMalformedIds() {
        val bad = listOf(
            "", "Root", "bible/", "bible/BSB", "bible/bsb/XYZ", "bible/bsb/gen", "bible/bsb/GEN/0",
            "bible/bsb/GEN/51", "bible/bsb/GEN/01", "bible/bsb/PSA/51-40", "bible/bsb/PSA/1-151",
            "bible/bsb/GEN/1/2", "bible/bsb/GEN/one", "plan/1", "podcast/x/y", "bible/bsb/OT/GEN",
        )
        for (s in bad) assertNull(MediaId.parse(s), s)
    }

    @Test
    fun constructorsValidate() {
        assertFailsWith<IllegalArgumentException> { MediaId.Version("Bad Id") }
        assertFailsWith<IllegalArgumentException> { MediaId.ChapterGroup("bsb", Book.PSALMS, 100, 51) }
    }
}
