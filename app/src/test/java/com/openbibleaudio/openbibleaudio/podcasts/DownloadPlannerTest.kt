package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import org.junit.Test
import kotlin.test.assertEquals

/** Download modes, queue order and retention (spec 0003 §8.1, §8.4). Pure JVM. */
class DownloadPlannerTest {
    private val day = 24L * 60 * 60 * 1000

    private fun ep(n: Int, state: DownloadState = DownloadState.NONE, length: Long? = 1_000) = PodcastEpisodeEntity(
        feedId = "feed00000000", guidHash = "%016x".format(n), guid = "g$n", title = "Day $n", pubDate = n.toLong(),
        enclosureUrl = "https://cdn.example.com/$n.mp3", enclosureLength = length, durationMs = null, episodeType = "full",
        cycle = 7, matchedDay = n, matchSource = null, inFeed = true, downloadState = state,
    )

    private fun enrollment(
        mode: DownloadMode,
        current: Int?,
        days: Map<Int, DayState> = emptyMap(),
        dayCount: Int = 10,
        rolling: Int = 3,
        episodes: Map<Int, PodcastEpisodeEntity> = (1..dayCount).associateWith { ep(it) },
        id: Long = 1,
    ) = EnrollmentEpisodes(id, mode, rolling, current, dayCount, days, episodes)

    private fun done(at: Long = 0) = DayState(DayStatus.COMPLETE, at)

    @Test
    fun allStartsAtTheCurrentDayWrapsAndSkipsFinishedDays() {
        val e = enrollment(DownloadMode.ALL, current = 4, days = mapOf(1 to done(), 2 to DayState(DayStatus.SKIPPED, null), 6 to done()))
        assertEquals(listOf(4, 5, 7, 8, 9, 10, 3), DownloadPlanner.wantedDays(e))
    }

    @Test
    fun rollingKeepsTheNextOpenDays() {
        val e = enrollment(DownloadMode.ROLLING, current = 4, days = mapOf(5 to done()), rolling = 3)
        assertEquals(listOf(4, 6, 7), DownloadPlanner.wantedDays(e))
        // In progress counts as open.
        val inProgress = enrollment(DownloadMode.ROLLING, current = 9, days = mapOf(9 to DayState(DayStatus.IN_PROGRESS, null)), rolling = 3)
        assertEquals(listOf(9, 10), DownloadPlanner.wantedDays(inProgress))
    }

    @Test
    fun offAndFinishedWantNothing() {
        assertEquals(emptyList(), DownloadPlanner.wantedDays(enrollment(DownloadMode.OFF, current = 1)))
        assertEquals(emptyList(), DownloadPlanner.wantedDays(enrollment(DownloadMode.ALL, current = null)))
    }

    @Test
    fun theQueueInterleavesEnrollmentsDedupesAndSkipsDoneOrFailed() {
        val shared = (1..10).associateWith { ep(it) }.toMutableMap()
        shared[2] = ep(2, DownloadState.DOWNLOADED)
        shared[3] = ep(3, DownloadState.FAILED)
        val a = enrollment(DownloadMode.ROLLING, current = 1, rolling = 4, episodes = shared, id = 1)
        val b = enrollment(DownloadMode.ROLLING, current = 6, rolling = 2, episodes = shared, id = 2)
        // a wants 1,2,3,4; b wants 6,7. Interleaved by rank: 1,6,2,7,3,4. Then 2 (downloaded) and 3 (failed) drop out.
        assertEquals(listOf(1, 6, 7, 4), DownloadPlanner.queue(listOf(a, b)).map { it.matchedDay })

        val overlap = enrollment(DownloadMode.ROLLING, current = 1, rolling = 2, episodes = shared, id = 3)
        assertEquals(listOf(1, 4), DownloadPlanner.queue(listOf(a, overlap)).map { it.matchedDay }.filter { it == 1 || it == 4 })
        assertEquals(1, DownloadPlanner.queue(listOf(a, overlap)).count { it.matchedDay == 1 })
    }

    @Test
    fun retentionWaitsForEveryEnrollmentAndTheRetentionPeriod() {
        val now = 100 * day
        val downloaded = listOf(ep(1, DownloadState.DOWNLOADED), ep(2, DownloadState.DOWNLOADED), ep(3, DownloadState.DOWNLOADED), ep(9, DownloadState.DOWNLOADED))
        val a = enrollment(
            DownloadMode.ALL, current = 4, dayCount = 3,
            days = mapOf(1 to done(now - 8 * day), 2 to done(now - 2 * day), 3 to DayState(DayStatus.SKIPPED, null)),
        )
        // Day 1: finished 8 days ago (> 7) → goes. Day 2: finished 2 days ago → stays. Day 3: skipped → goes.
        // Episode 9: no enrollment references it → goes.
        assertEquals(listOf(1, 3, 9), DownloadPlanner.deletable(downloaded, listOf(a), now, 7, keepPlayed = false).map { it.matchedDay })

        // A second enrollment still on day 1 keeps episode 1.
        val b = enrollment(DownloadMode.ROLLING, current = 1, dayCount = 3, id = 2)
        assertEquals(listOf(9), DownloadPlanner.deletable(downloaded, listOf(a, b), now, 7, keepPlayed = false).map { it.matchedDay })

        // keepPlayed keeps everything that's still referenced.
        assertEquals(listOf(9), DownloadPlanner.deletable(downloaded, listOf(a), now, 7, keepPlayed = true).map { it.matchedDay })
    }

    @Test
    fun estimatesFillGapsWithTheAverage() {
        // Known: 1 000 + 3 000; the two unknown ones count as the 2 000 average.
        assertEquals(8_000, DownloadPlanner.estimateBytes(listOf(1_000L, 3_000L, null, 0L)))
        assertEquals(0, DownloadPlanner.estimateBytes(listOf(null, null)))
    }

    @Test
    fun allIsPreselectedOnlyWithAGigabyteToSpare() {
        val gb = 1L shl 30
        assertEquals(DownloadMode.ALL, DownloadPlanner.defaultMode(3 * gb, 4 * gb))
        assertEquals(DownloadMode.ROLLING, DownloadPlanner.defaultMode(3 * gb, 4 * gb - 1))
        assertEquals(DownloadMode.ROLLING, DownloadPlanner.defaultMode(0, 10 * gb))
    }
}
