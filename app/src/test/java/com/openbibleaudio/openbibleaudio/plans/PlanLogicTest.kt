package com.openbibleaudio.openbibleaudio.plans

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.plan.EpisodeMatch
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PlanPodcast
import com.openbibleaudio.core.model.plan.PodcastPosition
import com.openbibleaudio.core.model.plan.Reading
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val start: LocalDate = LocalDate.of(2026, 11, 8)

class PlanProgressTest {
    private fun pos(statuses: Map<Int, DayStatus>, today: LocalDate = start, days: Int = 365) =
        PlanProgress.position(days, statuses, start, today)

    private fun done(vararg days: Int) = days.associateWith { DayStatus.COMPLETE }

    @Test
    fun freshEnrollmentStartsAtDayOne() {
        val p = pos(emptyMap())
        assertEquals(1, p.currentDay)
        assertEquals(1, p.calendarDay)
        assertEquals(0, p.behind)
        assertEquals(0, p.ahead)
        assertFalse(p.upNext)
    }

    @Test
    fun finishingTodaysDayMakesTheNextDayCurrentImmediately() {
        // Caught up: day 1 done on day 1 of the calendar. Day 2 is current and playable now.
        val p = pos(done(1))
        assertEquals(2, p.currentDay)
        assertEquals(1, p.calendarDay)
        assertTrue(p.upNext)
        assertEquals(0, p.ahead)
        assertEquals(0, p.behind)
    }

    @Test
    fun listeningAheadKeepsGoingAndCountsAhead() {
        val p = pos(done(1, 2, 3, 4))
        assertEquals(5, p.currentDay)
        assertTrue(p.upNext)
        assertEquals(3, p.ahead)
        assertEquals(PlanProgress.nextDayAfter(5, 365, done(1, 2, 3, 4)), 6)
    }

    @Test
    fun behindTheCalendar() {
        val p = pos(done(1), today = start.plusDays(4)) // calendar day 5
        assertEquals(2, p.currentDay)
        assertEquals(5, p.calendarDay)
        assertEquals(3, p.behind)
        assertFalse(p.upNext)
    }

    @Test
    fun calendarIsClamped() {
        assertEquals(1, pos(emptyMap(), today = start.minusDays(3)).calendarDay)
        assertEquals(365, pos(emptyMap(), today = start.plusDays(1000)).calendarDay)
    }

    @Test
    fun currentDaySkipsDoneAndSkippedButStopsAtInProgress() {
        val statuses = mapOf(1 to DayStatus.COMPLETE, 2 to DayStatus.SKIPPED, 3 to DayStatus.IN_PROGRESS)
        assertEquals(3, pos(statuses).currentDay)
        // An out-of-order day played ahead doesn't move the current day.
        assertEquals(1, pos(done(10)).currentDay)
    }

    @Test
    fun finishedWhenEveryDayIsDone() {
        val p = pos((1..3).associateWith { DayStatus.COMPLETE }, days = 3)
        assertNull(p.currentDay)
        assertTrue(p.finished)
        assertFalse(p.upNext)
        assertNull(PlanProgress.nextDayAfter(3, 3, emptyMap()))
    }

    @Test
    fun skipToTodayMarksOpenDaysBeforeTheCalendarDay() {
        val statuses = done(1) + (3 to DayStatus.IN_PROGRESS)
        val p = pos(statuses, today = start.plusDays(4)) // calendar day 5
        assertEquals(mapOf(2 to DayStatus.SKIPPED, 3 to DayStatus.SKIPPED, 4 to DayStatus.SKIPPED), PlanProgress.skipToToday(p, statuses))
        assertEquals(emptyMap(), PlanProgress.skipToToday(pos(done(1)), done(1)))
    }

    @Test
    fun dayStatusFromItems() {
        val readings = listOf("ch/GEN/1", "ch/GEN/2")
        val ep = "ep/0123456789abcdef"
        fun s(completed: Set<String>, episode: String? = ep, unavailable: Boolean = false) =
            PlanProgress.statusFromItems(readings, episode, completed, unavailable)
        assertEquals(DayStatus.PENDING, s(emptySet()))
        assertEquals(DayStatus.IN_PROGRESS, s(setOf("ch/GEN/1")))
        assertEquals(DayStatus.IN_PROGRESS, s(readings.toSet())) // episode still to play
        assertEquals(DayStatus.COMPLETE, s(readings.toSet() + ep))
        assertEquals(DayStatus.COMPLETE, s(readings.toSet(), episode = null)) // no episode in the queue
        assertEquals(DayStatus.COMPLETE, s(readings.toSet(), unavailable = true)) // failed download doesn't block
    }

    @Test
    fun manualStatusesStand() {
        assertEquals(DayStatus.COMPLETE, PlanProgress.merge(DayStatus.COMPLETE, DayStatus.IN_PROGRESS))
        assertEquals(DayStatus.SKIPPED, PlanProgress.merge(DayStatus.SKIPPED, DayStatus.PENDING))
        assertEquals(DayStatus.COMPLETE, PlanProgress.merge(DayStatus.IN_PROGRESS, DayStatus.COMPLETE))
        assertEquals(DayStatus.IN_PROGRESS, PlanProgress.merge(null, DayStatus.IN_PROGRESS))
    }
}

class PlanQueueBuilderTest {
    private val podcast = PlanPodcast("https://x.example/feed", match = EpisodeMatch(titleRegex = "^Day (\\d+)"))
    private val plan = PlanDefinition(
        1, "p", 1, "P",
        days = listOf(
            PlanDay(1, listOf(Reading(Book.GENESIS, 1, 3))),
            PlanDay(2, listOf(Reading(Book.NUMBERS, 14, 15), Reading(Book.PSALMS, 90))),
        ),
        podcast = podcast,
    )
    private val everything = generateSequence(ChapterRef(Book.GENESIS, 1)) { it.next() }.toSet()
    private val local = EpisodeAvailability.Local("0123456789ab", "0123456789abcdef", "file:///ep.mp3")

    @Test
    fun readingsInListedOrderThenEpisode() {
        val q = PlanQueueBuilder.build(7, plan, 2, everything, local)!!
        assertEquals(
            listOf(ChapterRef(Book.NUMBERS, 14), ChapterRef(Book.NUMBERS, 15), ChapterRef(Book.PSALMS, 90)),
            q.entries.filterIsInstance<QueueEntry.Chapter>().map { it.ref },
        )
        val ep = q.entries.last() as QueueEntry.Episode
        assertEquals("file:///ep.mp3", ep.uri)
        assertFalse(ep.streaming)
        assertEquals((0..3).map { MediaId.PlanItem(7, 2, it) }, q.entries.map { it.mediaId })
        assertEquals(listOf("ch/NUM/14", "ch/NUM/15", "ch/PSA/90"), q.readingKeys)
        assertEquals("ep/0123456789abcdef", q.episodeKey)
        assertNull(q.episodeUnavailable)
    }

    @Test
    fun episodeBeforeReadings() {
        val before = plan.copy(podcast = podcast.copy(position = PodcastPosition.BEFORE_READINGS))
        val q = PlanQueueBuilder.build(7, before, 1, everything, local)!!
        assertTrue(q.entries.first() is QueueEntry.Episode)
        assertEquals(MediaId.PlanItem(7, 1, 0), q.entries.first().mediaId)
        assertEquals(4, q.entries.size)
    }

    @Test
    fun missingChaptersAreDroppedAndReported() {
        val q = PlanQueueBuilder.build(7, plan, 1, everything - ChapterRef(Book.GENESIS, 2), local)!!
        assertEquals(listOf(1, 3), q.entries.filterIsInstance<QueueEntry.Chapter>().map { it.ref.chapter })
        assertEquals(listOf(ChapterRef(Book.GENESIS, 2)), q.missingChapters)
        assertEquals(listOf(0, 1, 2), q.entries.map { it.mediaId.index }) // indexes stay dense
    }

    @Test
    fun unavailableAndStreamingEpisodes() {
        val none = PlanQueueBuilder.build(7, plan, 1, everything, EpisodeAvailability.Unavailable(EpisodeUnavailable.NOT_DOWNLOADED))!!
        assertEquals(3, none.entries.size)
        assertNull(none.episodeKey)
        assertEquals(EpisodeUnavailable.NOT_DOWNLOADED, none.episodeUnavailable)

        val stream = PlanQueueBuilder.build(7, plan, 1, everything, EpisodeAvailability.Stream("0123456789ab", "0123456789abcdef", "https://cdn/ep.mp3"))!!
        assertTrue((stream.entries.last() as QueueEntry.Episode).streaming)

        val noPodcastPlan = plan.copy(podcast = null)
        val q = PlanQueueBuilder.build(7, noPodcastPlan, 1, everything, EpisodeAvailability.Unavailable(EpisodeUnavailable.NO_PODCAST))!!
        assertNull(q.episodeUnavailable)
    }

    @Test
    fun unknownDay() {
        assertNull(PlanQueueBuilder.build(7, plan, 3, everything, local))
    }
}
