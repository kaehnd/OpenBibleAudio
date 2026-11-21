package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.plans.EnrollmentSummary
import com.openbibleaudio.openbibleaudio.plans.PlanProgress
import com.openbibleaudio.openbibleaudio.plans.PlanResumption
import com.openbibleaudio.openbibleaudio.plans.PlanVoice
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeBrowseState
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val start = LocalDate.of(2026, 11, 8)

/** A 75-day plan; day n reads Genesis ((n − 1) mod 50) + 1. */
private val plan = PlanDefinition(
    1, "tbr", 1, "The Bible Recap (Chronological)",
    days = (1..75).map { PlanDay(it, listOf(Reading(Book.GENESIS, ((it - 1) % 50) + 1))) },
)

internal fun summary(
    id: Long,
    statuses: Map<Int, DayStatus>,
    today: LocalDate = start,
    state: EnrollmentState = EnrollmentState.ACTIVE,
    def: PlanDefinition = plan,
) = EnrollmentSummary(
    EnrollmentEntity(id, def.id, def.version, null, start.toString(), null, DownloadMode.OFF, 14, state, id),
    def, statuses, PlanProgress.position(def.dayCount, statuses, start, today),
)

private fun done(vararg days: Int) = days.associateWith { DayStatus.COMPLETE }

class PlanBrowseTreeTest {
    private val tree = BrowseTree(Titles)
    private val snap = LibrarySnapshot.EMPTY_LIBRARY

    @Test
    fun noEnrollmentsShowAnInfoItem() {
        val empty = PlanBrowseState.EMPTY
        assertEquals(listOf(BrowseTree.NO_PLANS_ID), tree.children(MediaId.Today, snap, empty)!!.map { it.id })
        assertEquals(listOf(BrowseTree.NO_PLANS_ID), tree.children(MediaId.Plans, snap, empty)!!.map { it.id })
        // With only a resumption item, Today shows just that.
        val resume = PlanBrowseState(emptyList(), ResumeEntry("bible/bsb/GEN/12", "GEN 12"))
        val only = tree.children(MediaId.Today, snap, resume)!!.single()
        assertEquals("bible/bsb/GEN/12", only.id)
        assertEquals("Continue: GEN 12", only.title)
        assertTrue(only.playable)
    }

    @Test
    fun todayListsEachEnrollmentsCurrentDayThenResume() {
        val state = PlanBrowseState(
            listOf(summary(3, done(1, 2)), summary(4, emptyMap(), today = start.plusDays(3))),
            ResumeEntry("plan/3/day/2/0", "x"),
        )
        val today = tree.children(MediaId.Today, snap, state)!!
        assertEquals(listOf("plan/3/today", "plan/4/today", "plan/3/day/2/0"), today.map { it.id })
        // Caught up: day 3 is current and labelled "(up next)", playable straight away.
        assertEquals("The Bible Recap (Chronological) · Day 3 (up next)", today[0].title)
        assertEquals("GEN 3", today[0].subtitle)
        assertTrue(today[0].playable)
        assertEquals("The Bible Recap (Chronological) · Day 1", today[1].title)

        val plans = tree.children(MediaId.Plans, snap, state)!!
        assertEquals(listOf("plan/3", "plan/4"), plans.map { it.id })
        assertEquals("Day 1 · 3 behind", plans[1].subtitle)
    }

    @Test
    fun todaysSubtitleCarriesTheEpisodeStateForPodcastPlansOnly() {
        val podcastPlan = plan.copy(podcast = com.openbibleaudio.core.model.plan.PlanPodcast("https://feed.example.com/x.xml", match = com.openbibleaudio.core.model.plan.EpisodeMatch(titleRegex = "^Day (\\d+)")))
        val state = PlanBrowseState(
            listOf(summary(3, emptyMap(), def = podcastPlan), summary(4, emptyMap())),
            null,
            episodes = mapOf(3L to EpisodeBrowseState.DOWNLOADED, 4L to EpisodeBrowseState.NOT_DOWNLOADED),
        )
        val today = tree.children(MediaId.Today, snap, state)!!
        assertEquals("GEN 1 · DOWNLOADED", today[0].subtitle)
        // Plan 4 has no podcast, so a stray state is ignored.
        assertEquals("GEN 1", today[1].subtitle)
        assertEquals("GEN 1 · DOWNLOADED", tree.node(MediaId.EnrollmentToday(3), snap, state)!!.subtitle)
    }

    @Test
    fun finishedEnrollmentHasNoTodayButKeepsItsDays() {
        val all = (1..75).associateWith { DayStatus.COMPLETE }
        val state = PlanBrowseState(listOf(summary(3, all, state = EnrollmentState.FINISHED)), null)
        assertEquals(listOf(BrowseTree.NO_PLANS_ID), tree.children(MediaId.Today, snap, state)!!.map { it.id })
        assertEquals("Finished", tree.node(MediaId.Enrollment(3), snap, state)!!.subtitle)
        assertEquals(listOf("plan/3/days/1-30", "plan/3/days/31-60", "plan/3/days/61-75"), tree.children(MediaId.Enrollment(3), snap, state)!!.map { it.id })
    }

    @Test
    fun dayGroupsOf30WithTheCurrentGroupFirst() {
        val state = PlanBrowseState(listOf(summary(3, (1..40).associateWith { DayStatus.COMPLETE })), null)
        val children = tree.children(MediaId.Enrollment(3), snap, state)!!
        assertEquals(listOf("plan/3/today", "plan/3/days/31-60", "plan/3/days/1-30", "plan/3/days/61-75"), children.map { it.id })
        assertTrue(children.drop(1).all { it.browsable && !it.playable && it.listChildren })

        val days = tree.children(MediaId.DayGroup(3, 31, 60), snap, state)!!
        assertEquals(30, days.size)
        assertEquals("plan/3/day/31", days.first().id)
        assertEquals("Day 31 ✓", days.first().title)
        assertEquals("Day 41", days[10].title)
        assertTrue(days.all { it.playable })
        // A group that doesn't exist isn't served.
        assertNull(tree.children(MediaId.DayGroup(3, 2, 31), snap, state))
        assertNull(tree.children(MediaId.Enrollment(99), snap, state))
    }

    @Test
    fun everyPlanNodeCanBeRebuiltFromItsId() {
        val state = PlanBrowseState(listOf(summary(3, mapOf(2 to DayStatus.SKIPPED))), null)
        val ids = listOf(
            MediaId.Today, MediaId.Plans, MediaId.Enrollment(3), MediaId.EnrollmentToday(3),
            MediaId.DayGroup(3, 61, 75), MediaId.PlanDay(3, 2),
        )
        for (id in ids) assertEquals(id.format(), tree.node(id, snap, state)!!.id)
        assertEquals("Day 2 skipped", tree.node(MediaId.PlanDay(3, 2), snap, state)!!.title)
        assertNull(tree.node(MediaId.PlanDay(3, 76), snap, state))
        assertNull(tree.node(MediaId.PlanItem(3, 1, 0), snap, state))
    }
}

class PlanVoiceTest {
    private val recap = summary(3, emptyMap())
    private val other = summary(4, emptyMap(), def = plan.copy(id = "m", name = "M'Cheyne"))

    @Test
    fun genericPhrasesPickTheLastPlayedEnrollment() {
        val list = listOf(recap, other)
        assertEquals(3, PlanVoice.match("today", list, lastPlayed = null))
        assertEquals(4, PlanVoice.match("Today's reading", list, lastPlayed = 4))
        assertEquals(4, PlanVoice.match("play my plan", list, lastPlayed = 4))
        assertEquals(3, PlanVoice.match("today", list, lastPlayed = 99))
    }

    @Test
    fun planNamesMatch() {
        val list = listOf(recap, other)
        assertEquals(3, PlanVoice.match("the bible recap", list, null))
        assertEquals(3, PlanVoice.match("Play The Bible Recap", list, null))
        assertEquals(4, PlanVoice.match("mcheyne", list, null))
    }

    @Test
    fun otherQueriesFallThroughToBibleSearch() {
        val list = listOf(recap)
        assertNull(PlanVoice.match("genesis 3", list, null))
        assertNull(PlanVoice.match("bible", list, null)) // one word of the name isn't enough
        assertNull(PlanVoice.match("", list, null))
        assertNull(PlanVoice.match("today", emptyList(), null))
        // A finished or paused enrollment can't take "today".
        assertNull(PlanVoice.match("today", listOf(summary(3, emptyMap(), state = EnrollmentState.PAUSED)), null))
    }
}

class PlanResumptionTest {
    @Test
    fun openDayResumesAtTheSavedItem() {
        assertEquals(PlanResumption.Target(5, 2, 30_000), PlanResumption.target(5, 2, 30_000, mapOf(5 to DayStatus.IN_PROGRESS), 5))
        assertEquals(PlanResumption.Target(7, 1, 10), PlanResumption.target(7, 1, 10, emptyMap(), 3)) // out-of-order day
    }

    @Test
    fun completedDayResumesTheCurrentDayFromItsStart() {
        assertEquals(PlanResumption.Target(6, 0, 0), PlanResumption.target(5, 2, 30_000, mapOf(5 to DayStatus.COMPLETE), 6))
        assertEquals(PlanResumption.Target(6, 0, 0), PlanResumption.target(5, 2, 30_000, mapOf(5 to DayStatus.SKIPPED), 6))
        assertNull(PlanResumption.target(5, 2, 30_000, mapOf(5 to DayStatus.COMPLETE), null))
    }
}
