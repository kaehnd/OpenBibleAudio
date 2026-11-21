package com.openbibleaudio.openbibleaudio.podcasts

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.core.podcast.EpisodeMatcher
import com.openbibleaudio.core.podcast.FeedItem
import com.openbibleaudio.core.podcast.FeedParser
import com.openbibleaudio.core.podcast.MatchSource
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed → snapshot merge (spec 0003 §7.3) over the TBR-shaped fixture. Robolectric only for
 * Android's XmlPullParser, the one the app uses.
 */
@RunWith(AndroidJUnit4::class)
class FeedSnapshotTest {
    private val podcast = TbrShapedFeed.podcast
    private val days = TbrShapedFeed.DAY_COUNT
    private val feedId = PodcastIds.feedId(TbrShapedFeed.FEED_URL)
    private val items: List<FeedItem> = FeedParser.parse(TbrShapedFeed.xml.byteInputStream(), Xml.newPullParser()).items

    private fun merge(items: List<FeedItem>, stored: List<PodcastEpisodeEntity> = emptyList()) =
        FeedSnapshot.merge(feedId, podcast, days, items, stored)

    private fun List<EpisodeMetadata>.byGuid() = associateBy { it.guid }

    @Test
    fun firstRefreshMatchesEveryCycle() {
        val rows = merge(items).byGuid()
        for (year in 6..7) for (day in 1..5) {
            val variant = if (year == 6 && day == 5) "-reupload" else ""
            val row = rows.getValue(TbrShapedFeed.guid(year, day, variant))
            assertEquals(year, row.cycle, row.title)
            assertEquals(day, row.matchedDay, row.title)
            assertEquals(MatchSource.TITLE, row.matchSource)
        }
        // Year 8 day 2 is titled "Year 7" but tagged season 8.
        assertEquals(8 to 2, rows.getValue(TbrShapedFeed.guid(8, 2)).let { it.cycle to it.matchedDay })
        assertTrue(rows.values.all { it.inFeed })
        assertEquals(PodcastIds.guidHash(TbrShapedFeed.guid(7, 1)), rows.getValue(TbrShapedFeed.guid(7, 1)).guidHash)
    }

    @Test
    fun duplicatesAndBonusItemsHaveNoDay() {
        val rows = merge(items).byGuid()
        // The earlier Year 6 day 5 upload loses to the re-upload.
        assertNull(rows.getValue(TbrShapedFeed.guid(6, 5)).matchedDay)
        assertNull(rows.getValue("recap.example.com/bonus-jan-8").matchedDay)
        assertEquals(8, rows.getValue("recap.example.com/bonus-jan-8").cycle)
        assertNull(rows.getValue("recap.example.com/kneecap").matchedDay)
    }

    @Test
    fun cyclesComeFromStoredRowsAndDefaultToLatestComplete() {
        val cycles = FeedSnapshot.cycles(podcast, days, merge(items).map { it.toNewEntity() })
        assertEquals(listOf(8, 7, 6), cycles.map { it.key })
        assertEquals(listOf(3, 5, 5), cycles.map { it.matchedDays })
        assertEquals(listOf(2026, 2025, 2024), cycles.map { it.year })
        assertEquals(7, EpisodeMatcher.select(cycles, podcast)?.key)
    }

    @Test
    fun itemsThatLeaveTheFeedAreKeptWithTheirMatch() {
        val first = merge(items).map { it.toNewEntity() }
        // The feed's item window moves on: Year 6 drops out.
        val later = merge(items.filterNot { it.season == 6 }, first).byGuid()

        val y6d1 = later.getValue(TbrShapedFeed.guid(6, 1))
        assertEquals(false, y6d1.inFeed)
        assertEquals(6 to 1, y6d1.cycle to y6d1.matchedDay)
        assertTrue(later.getValue(TbrShapedFeed.guid(7, 1)).inFeed)
        assertEquals(first.size, later.size)

        val cycles = FeedSnapshot.cycles(podcast, days, later.values.map { it.toNewEntity() })
        assertEquals(5, cycles.single { it.key == 6 }.matchedDays)
    }

    @Test
    fun aNewerLiveItemTakesTheDayFromASnapshottedOne() {
        val first = merge(items).map { it.toNewEntity() }
        val old = items.single { it.guid == TbrShapedFeed.guid(7, 1) }
        // Year 7 day 1 dropped out of the feed and was then re-uploaded under a new GUID.
        val reupload = old.copy(guid = "recap.example.com/y7-d1-new", enclosureUrl = "https://cdn.example.com/new.mp3", pubDateMs = old.pubDateMs!! + 1)
        val rows = merge(items.filterNot { it.guid == old.guid } + reupload, first).byGuid()

        assertNull(rows.getValue(old.guid).matchedDay)
        assertEquals(1, rows.getValue(reupload.guid).matchedDay)
    }

    @Test
    fun aPlanOverrideBeatsATitleMatch() {
        val bonus = "recap.example.com/bonus-jan-8"
        val withOverride = podcast.copy(overrides = mapOf("8/1" to com.openbibleaudio.core.model.plan.EpisodeOverride(bonus)))
        val rows = FeedSnapshot.merge(feedId, withOverride, days, items, emptyList()).byGuid()

        assertEquals(1, rows.getValue(bonus).matchedDay)
        assertEquals(MatchSource.PLAN_OVERRIDE, rows.getValue(bonus).matchSource)
        assertNull(rows.getValue(TbrShapedFeed.guid(8, 1)).matchedDay)
    }

    @Test
    fun aPlanWithoutACycleBlockHasOneCycle() {
        val noCycles = podcast.copy(cycle = null)
        val rows = FeedSnapshot.merge(feedId, noCycles, days, items, emptyList())
        assertTrue(rows.all { it.cycle == null })
        val cycles = FeedSnapshot.cycles(noCycles, days, rows.map { it.toNewEntity() })
        assertEquals(listOf<Int?>(null), cycles.map { it.key })
        assertEquals(5, cycles.single().matchedDays)
    }
}
