package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.plans.PlanCard
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone's enrollment card (spec 0003 §9.3), using the 75-day plan from PlanBrowseTreeTest. */
class PlanCardTest {
    private val start = LocalDate.of(2026, 11, 8)

    @Test
    fun caughtUpSaysKeepGoingWithTheNextDay() {
        val card = PlanCard.from(summary(3, mapOf(1 to DayStatus.COMPLETE)))
        assertEquals(2, card.currentDay)
        assertEquals(2, card.keepGoingDay)
        assertNull(card.badge)
        assertEquals(1, card.daysDone)
    }

    @Test
    fun behindAndAheadBadges() {
        val behind = PlanCard.from(summary(3, emptyMap(), today = start.plusDays(3)))
        assertEquals(PlanCard.Badge.Behind(3), behind.badge)
        assertNull(behind.keepGoingDay)

        val ahead = PlanCard.from(summary(3, (1..4).associateWith { DayStatus.COMPLETE }))
        assertEquals(PlanCard.Badge.Ahead(3), ahead.badge)
        assertEquals(5, ahead.keepGoingDay)
    }

    @Test
    fun finishedAndPaused() {
        val all = (1..75).associateWith { if (it % 2 == 0) DayStatus.SKIPPED else DayStatus.COMPLETE }
        val done = PlanCard.from(summary(3, all, state = EnrollmentState.FINISHED))
        assertTrue(done.finished)
        assertNull(done.badge)
        assertNull(done.keepGoingDay)
        assertEquals(75, done.daysDone)

        assertTrue(PlanCard.from(summary(3, emptyMap(), state = EnrollmentState.PAUSED)).paused)
    }
}
