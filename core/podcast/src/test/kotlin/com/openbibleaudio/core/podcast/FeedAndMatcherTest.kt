package com.openbibleaudio.core.podcast

import com.openbibleaudio.core.model.plan.CycleKey
import com.openbibleaudio.core.model.plan.CycleRule
import com.openbibleaudio.core.model.plan.CycleSelect
import com.openbibleaudio.core.model.plan.EpisodeOverride
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed.guid
import org.kxml2.io.KXmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedAndMatcherTest {
    private val feed = FeedParser.parse(TbrShapedFeed.xml.byteInputStream(), KXmlParser())
    private val podcast = TbrShapedFeed.podcast
    private val days = TbrShapedFeed.DAY_COUNT

    @Test
    fun parsesChannelAndItems() {
        assertEquals("The Recap (fixture)", feed.title)
        // 17 audio items; the PDF item and the item without an enclosure are dropped.
        assertEquals(17, feed.items.size)
        val first = feed.items.first()
        assertEquals("Day 003 (Genesis 8-11) -  Year 8", first.title)
        assertEquals(guid(8, 3), first.guid)
        assertEquals("full", first.episodeType)
        assertEquals(8, first.season)
        assertEquals(3, first.episode) // "003"
        assertEquals(7_000_000, first.enclosureLength)
        assertEquals(468_000, first.durationMs)
        assertEquals(java.time.ZonedDateTime.parse("2026-01-03T01:00:00-06:00").toInstant().toEpochMilli(), first.pubDateMs)
        assertEquals("January Reflections & Corrections - Year 8", feed.items[2].title)
        assertEquals(468_000, feed.items.first { it.guid == guid(7, 4) }.durationMs) // "0:07:48"
        val kneecap = feed.items.first { it.title.contains("KNEEcap") }
        assertNull(kneecap.season)
        assertEquals(366, kneecap.episode)
    }

    @Test
    fun dateAndDurationFormats() {
        assertEquals(FeedParser.parseDate("Wed, 01 Jan 2025 00:00:00 -0600"), FeedParser.parseDate("01 Jan 2025 00:00:00 -0600"))
        assertEquals(FeedParser.parseDate("Wed, 01 Jan 2025 06:00:00 GMT"), FeedParser.parseDate("Wed,  1 Jan 2025 00:00:00 -0600"))
        assertNull(FeedParser.parseDate("yesterday"))
        assertEquals(3_723_000, FeedParser.parseDuration("1:02:03"))
        assertEquals(90_000, FeedParser.parseDuration("01:30"))
        assertEquals(411_000, FeedParser.parseDuration("411"))
        assertNull(FeedParser.parseDuration(""))
        assertNull(FeedParser.parseDuration("abc"))
    }

    @Test
    fun rejectsNonFeeds() {
        assertFailsWith<FeedFormatException> { FeedParser.parse("<html><body/></html>".byteInputStream(), KXmlParser()) }
        assertFailsWith<FeedFormatException> { FeedParser.parse("<rss><channel>".byteInputStream(), KXmlParser()) }
        assertFailsWith<FeedFormatException> {
            FeedParser.parse(TbrShapedFeed.xml.byteInputStream(), KXmlParser(), maxBytes = 1000)
        }
    }

    @Test
    fun matchesDaysByTitleAndCyclesBySeason() {
        val catalog = EpisodeMatcher.catalog(feed.items, podcast, days)
        assertEquals(listOf(8, 7, 6), catalog.map { it.key })
        val (y8, y7, y6) = catalog
        assertEquals(listOf(1, 2, 3), y8.byDay.keys.sorted())
        assertEquals(guid(8, 2), y8.byDay.getValue(2).item.guid) // titled "Year 7", season 8 wins
        assertFalse(y8.complete)
        assertEquals(listOf(4, 5), y8.missingDays)
        assertTrue(y7.complete)
        assertEquals(2025, y7.year)
        assertEquals(5 * 7_000_000L, y7.totalBytes)
        assertTrue(y6.complete)
        assertEquals(guid(6, 5, "-reupload"), y6.byDay.getValue(5).item.guid) // later upload wins
        assertEquals(listOf(guid(6, 5)), y6.duplicates.getValue(5).map { it.guid })
        // Bonus items and the season-less KNEEcap item are never matched.
        val matched = catalog.flatMap { c -> c.byDay.values.map { it.item.title } }
        assertTrue(matched.none { "KNEEcap" in it || "Reflections" in it || "Prep" in it })
    }

    @Test
    fun selectsLatestCompleteByDefault() {
        val catalog = EpisodeMatcher.catalog(feed.items, podcast, days)
        assertEquals(7, EpisodeMatcher.select(catalog, podcast)!!.key)
        assertEquals(8, EpisodeMatcher.select(catalog, CycleSelect.LATEST)!!.key)
        // Nothing complete → most matched days.
        val partial = catalog.map { if (it.complete) it.copy(byDay = it.byDay - 5) else it }
        assertEquals(7, EpisodeMatcher.select(partial, CycleSelect.LATEST_COMPLETE)!!.key) // 4 vs 4 vs 3: newest of the tie
        assertNull(EpisodeMatcher.select(emptyList(), CycleSelect.LATEST_COMPLETE))
    }

    @Test
    fun fallbackTitleRegexAndOtherKeys() {
        val noSeason = feed.items.map { it.copy(season = null) }
        val catalog = EpisodeMatcher.catalog(noSeason, podcast, days)
        // Without seasons, Year 8 day 2's title ("Year 7") puts it in cycle 7, as a duplicate.
        assertEquals(listOf(8, 7, 6), catalog.map { it.key })
        assertEquals(listOf(1, 3), catalog[0].byDay.keys.sorted())
        assertEquals(1, catalog[1].duplicates.size)

        val byYear = podcast.copy(cycle = CycleRule(CycleKey.PUB_DATE_YEAR))
        assertEquals(listOf(2026, 2025, 2024), EpisodeMatcher.catalog(feed.items, byYear, days).map { it.key })

        val noCycle = podcast.copy(cycle = null)
        val single = EpisodeMatcher.catalog(feed.items, noCycle, days).single()
        assertNull(single.key)
        assertEquals(guid(8, 3), single.byDay.getValue(3).item.guid) // newest wins across years
    }

    @Test
    fun precedenceIsManualThenOverrideThenTitle() {
        val withOverride = podcast.copy(overrides = mapOf("7/2" to EpisodeOverride(guid(6, 2))))
        val y7 = EpisodeMatcher.catalog(feed.items, withOverride, days).first { it.key == 7 }
        assertEquals(guid(6, 2), y7.byDay.getValue(2).item.guid)
        assertEquals(MatchSource.PLAN_OVERRIDE, y7.byDay.getValue(2).source)
        assertEquals(MatchSource.TITLE, y7.byDay.getValue(1).source)

        val pinned = y7.withPins(mapOf(2 to guid(8, 2), 1 to "unknown-guid"), feed.items)
        assertEquals(guid(8, 2), pinned.byDay.getValue(2).item.guid)
        assertEquals(MatchSource.MANUAL, pinned.byDay.getValue(2).source)
        assertEquals(guid(7, 1), pinned.byDay.getValue(1).item.guid) // unknown pin ignored

        // Pinning an episode to another day moves it rather than playing it twice.
        val moved = y7.withPins(mapOf(3 to guid(7, 1)), feed.items)
        assertEquals(guid(7, 1), moved.byDay.getValue(3).item.guid)
        assertFalse(1 in moved.byDay)
    }
}
