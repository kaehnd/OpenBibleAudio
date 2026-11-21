package com.openbibleaudio.openbibleaudio.ui

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.DebugEntryPoint
import com.openbibleaudio.openbibleaudio.MainActivity
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The phone's Plans and Enrollment screens against the real Hilt graph (spec 0003 §9.3): the
 * caught-up card, the day list, and a day's "Mark complete".
 */
@RunWith(AndroidJUnit4::class)
class PlansScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java)
    private val plan = PlanDefinition(
        1, "test-ui-plan", 1, "Test UI Plan",
        days = listOf(
            PlanDay(1, listOf(Reading(Book.GENESIS, 1, 2))),
            PlanDay(2, listOf(Reading(Book.GENESIS, 3))),
            PlanDay(3, listOf(Reading(Book.PSALMS, 1))),
        ),
    )
    private var enrollment = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        val plans = app.planRepository()
        plans.importPlan(PlanCodec.encode(plan))
        enrollment = plans.enroll("test-ui-plan", 1)
        plans.markComplete(enrollment, 1)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        app.planRepository().unenroll(enrollment)
    }

    @Test
    fun caughtUpCardDayListAndMarkComplete() {
        compose.onNode(hasText("Plans") and hasClickAction()).performClick()

        // Day 1 is done on day 1 of the calendar: day 2 is current and offered straight away.
        // The enrollment card comes before the "Imported plans" row of the same name.
        compose.onAllNodesWithText("Test UI Plan")[0].assertExists()
        compose.onNodeWithText("Today's reading done ✓ — keep going with Day 2").assertExists()
        compose.onNodeWithText("1 of 3 days done").assertExists()

        compose.onAllNodesWithText("Test UI Plan")[0].performClick()
        compose.onNodeWithText("Genesis 1–2").assertExists()
        compose.onNodeWithText("Genesis 3").assertExists()
        compose.onNodeWithText("Psalms 1").assertExists()

        // Menus: [header, day 1, day 2, day 3]. Mark day 2 complete from its menu.
        compose.onAllNodesWithContentDescription("More options")[2].performClick()
        compose.onNodeWithText("Mark complete").performClick()
        compose.waitUntil(10_000) {
            runBlocking { app.planRepository().summary(enrollment)?.statuses?.get(2) } == DayStatus.COMPLETE
        }
        compose.onNodeWithText("Today's reading done ✓ — keep going with Day 3").assertExists()
        assertEquals(3, runBlocking { app.planRepository().summary(enrollment)?.position?.currentDay })
    }
}
