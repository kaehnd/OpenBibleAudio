package com.openbibleaudio.openbibleaudio.podcasts

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.core.podcast.FeedParser
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.ManualPinEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An enrollment's per-day episodes, duplicates and pin candidates (spec 0003 §7.3, §7.4, §9.3). */
@RunWith(AndroidJUnit4::class)
class DayEpisodesTest {
    private val plan = TbrShapedFeed.plan
    private val feedId = PodcastIds.feedId(TbrShapedFeed.FEED_URL)
    private val rows: List<PodcastEpisodeEntity> = FeedSnapshot.merge(
        feedId, plan.podcast!!, plan.dayCount,
        FeedParser.parse(TbrShapedFeed.xml.byteInputStream(), Xml.newPullParser()).items, emptyList(),
    ).map { it.toNewEntity() }

    private fun hash(year: Int, day: Int, variant: String = "") = PodcastIds.guidHash(TbrShapedFeed.guid(year, day, variant))

    @Test
    fun eachDayGetsItsCyclesEpisode() {
        val v = DayEpisodes.view(plan, 7, rows, emptyList())
        assertEquals((1..5).toSet(), v.byDay.keys)
        assertEquals(hash(7, 3), v.byDay.getValue(3).episode.guidHash)
        assertTrue(v.byDay.values.none { it.pinned })
        assertEquals(emptySet(), v.duplicatedDays)
        // Year 7's only unused item is the "Prep" bonus (season 7).
        assertEquals(listOf("recap.example.com/prep-2026"), v.unassigned.map { it.guid })
        assertEquals(rows.size, v.all.size)
        assertTrue(v.all.zipWithNext().all { (a, b) -> a.pubDate >= b.pubDate })
    }

    @Test
    fun aDuplicateUploadFlagsItsDayAndIsOfferedForPinning() {
        val v = DayEpisodes.view(plan, 6, rows, emptyList())
        assertEquals(setOf(5), v.duplicatedDays)
        assertEquals(hash(6, 5, "-reupload"), v.byDay.getValue(5).episode.guidHash)
        assertTrue(hash(6, 5) in v.unassigned.map { it.guidHash })
        assertTrue(DayEpisodeInfo.of(5, v).needsAttention)
    }

    @Test
    fun aPinReplacesTheMatchAndClearsTheFlag() {
        val pin = ManualPinEntity(1, 5, feedId, hash(6, 5))
        val v = DayEpisodes.view(plan, 6, rows, listOf(pin))
        val day5 = v.byDay.getValue(5)
        assertEquals(hash(6, 5), day5.episode.guidHash)
        assertTrue(day5.pinned)
        assertFalse(DayEpisodeInfo.of(5, v).needsAttention)
        // The re-upload it displaced is now a candidate.
        assertTrue(hash(6, 5, "-reupload") in v.unassigned.map { it.guidHash })
    }

    @Test
    fun aPartialCycleFlagsItsUnmatchedDays() {
        val v = DayEpisodes.view(plan, 8, rows, emptyList())
        assertEquals(listOf(4, 5), v.unmatchedDays(plan.dayCount))
        val info = DayEpisodeInfo.of(4, v)
        assertEquals(DayEpisodeStatus.NONE, info.status)
        assertTrue(info.needsAttention)
    }

    @Test
    fun noCycleYetMeansNoEpisodes() {
        val v = DayEpisodes.view(plan, null, rows, emptyList())
        assertEquals(emptyMap(), v.byDay)
        assertEquals(rows.size, v.all.size) // "Show all" still works
    }

    @Test
    fun progressCountsDownloadedEpisodesAndTheirSize() {
        val downloaded = rows.map {
            if (it.guidHash == hash(7, 1) || it.guidHash == hash(7, 2)) it.copy(downloadState = DownloadState.DOWNLOADED, downloadedBytes = 1_000) else it
        }
        val p = EpisodeProgress.of(DayEpisodes.view(plan, 7, downloaded, emptyList()), plan.dayCount)
        assertEquals(EpisodeProgress(downloaded = 2, matched = 5, dayCount = 5, downloadedBytes = 2_000), p)
        assertEquals(DayEpisodeStatus.DOWNLOADED, DayEpisodeInfo.of(1, DayEpisodes.view(plan, 7, downloaded, emptyList())).status)
    }
}
