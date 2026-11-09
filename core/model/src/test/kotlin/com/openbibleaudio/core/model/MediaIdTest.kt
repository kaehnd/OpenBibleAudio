package com.openbibleaudio.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            MediaId.Today,
            MediaId.Plans,
            MediaId.Enrollment(3),
            MediaId.EnrollmentToday(3),
            MediaId.DayGroup(3, 1, 30),
            MediaId.DayGroup(3, 361, 365),
            MediaId.PlanDay(3, 1),
            MediaId.PlanDay(12, 365),
            MediaId.PlanItem(3, 47, 0),
            MediaId.PlanItem(3, 47, 12),
            MediaId.Episode(PodcastIds.feedId("https://feed.example.com/feed.xml"), PodcastIds.guidHash("guid-1")),
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
            "bible/bsb/GEN/1/2", "bible/bsb/GEN/one", "podcast/x/y", "bible/bsb/OT/GEN",
            "plan/", "plan/0", "plan/01", "plan/x", "plan/3/Today", "plan/3/day", "plan/3/day/0", "plan/3/day/01",
            "plan/3/day/1/01", "plan/3/day/1/-1", "plan/3/day/1/2/3", "plan/3/days/30-1", "plan/3/days/1",
            "plan/3/days/0-30", "plan/3/other", "today/1", "podcast/0123456789ab", "podcast/0123456789AB/0123456789abcdef",
            "podcast/0123456789ab/0123456789abcde",
        )
        for (s in bad) assertNull(MediaId.parse(s), s)
    }

    @Test
    fun planAndPodcastFormats() {
        assertEquals("today", MediaId.Today.format())
        assertEquals("plan", MediaId.Plans.format())
        assertEquals("plan/3/today", MediaId.EnrollmentToday(3).format())
        assertEquals("plan/3/days/31-60", MediaId.DayGroup(3, 31, 60).format())
        assertEquals("plan/3/day/47/2", MediaId.PlanItem(3, 47, 2).format())
        assertEquals(MediaId.PlanDay(3, 47), MediaId.PlanItem(3, 47, 2).dayId)
        assertEquals("podcast/0123456789ab/0123456789abcdef", MediaId.Episode("0123456789ab", "0123456789abcdef").format())
    }

    @Test
    fun podcastIdsAreStableHashes() {
        val url = "https://feed.podbean.com/thebiblerecap/feed.xml"
        assertEquals(PodcastIds.feedId(url), PodcastIds.feedId(url))
        assertTrue(PodcastIds.FEED_ID.matches(PodcastIds.feedId(url)))
        assertTrue(PodcastIds.GUID_HASH.matches(PodcastIds.guidHash("thebiblerecap.podbean.com/abc")))
        assertNotEquals(PodcastIds.guidHash("a"), PodcastIds.guidHash("b"))
    }

    @Test
    fun constructorsValidate() {
        assertFailsWith<IllegalArgumentException> { MediaId.Enrollment(0) }
        assertFailsWith<IllegalArgumentException> { MediaId.DayGroup(1, 30, 1) }
        assertFailsWith<IllegalArgumentException> { MediaId.PlanItem(1, 1, -1) }
        assertFailsWith<IllegalArgumentException> { MediaId.Episode("x", "y") }
        assertFailsWith<IllegalArgumentException> { MediaId.Version("Bad Id") }
        assertFailsWith<IllegalArgumentException> { MediaId.ChapterGroup("bsb", Book.PSALMS, 100, 51) }
    }
}
