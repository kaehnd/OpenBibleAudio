package com.openbibleaudio.openbibleaudio.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.DebugEntryPoint
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import com.openbibleaudio.openbibleaudio.library.ImportPreview
import com.openbibleaudio.openbibleaudio.library.MiniPack
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.plans.PlanImportResult
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Plans end to end on a device (spec 0003 §10.2): enroll in a 3-day plan over the mini pack,
 * browse Today and Plans, play a day through PlaybackService, and check that completing its items
 * completes the day, the next day is current straight away, and "continue to next day" runs on.
 */
@RunWith(AndroidJUnit4::class)
class PlanPlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java)
    private val versionId = "test-plan-mini"
    private val chapters = listOf(ChapterRef(Book.GENESIS, 1), ChapterRef(Book.GENESIS, 2), ChapterRef(Book.GENESIS, 3), ChapterRef(Book.PSALMS, 1))
    private val plan = PlanDefinition(
        1, "test-plan", 1, "Test Plan",
        days = listOf(
            PlanDay(1, listOf(Reading(Book.GENESIS, 1, 2))),
            PlanDay(2, listOf(Reading(Book.GENESIS, 3))),
            PlanDay(3, listOf(Reading(Book.PSALMS, 1))),
        ),
    )
    private lateinit var packDir: File
    private var enrollment = 0L
    private var browser: MediaBrowser? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        app.importController().delete(versionId)
        packDir = MiniPack.create(File(context.cacheDir, "plan-mini-pack"), chapters, versionId = versionId)
        val imports = app.importController()
        val preview = imports.inspect(Uri.fromFile(packDir))
        assertIs<ImportPreview.Ready>(preview, "$preview")
        imports.start(preview, move = false)
        withTimeout(60_000) {
            while (app.libraryRepository().version(versionId)?.state != VersionState.READY) delay(200)
        }
        val plans = app.planRepository()
        val imported = plans.importPlan(PlanCodec.encode(plan))
        assertTrue(imported is PlanImportResult.Imported || imported is PlanImportResult.AlreadyImported, "$imported")
        enrollment = plans.enroll("test-plan", 1, versionId = versionId)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { browser?.release() }
        app.settingsRepository().setContinueToNextDay(false)
        app.planRepository().unenroll(enrollment)
        app.importController().delete(versionId)
        packDir.deleteRecursively()
    }

    /** Waits for [done], failing with [state] (evaluated at the deadline) so a timeout says what was seen. */
    private suspend fun until(what: String, timeoutMs: Long = 30_000, state: suspend () -> Any?, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; saw ${state()}")
            delay(100)
        }
    }

    private suspend fun waitForDay(day: Int, status: DayStatus) =
        until("day $day $status", state = { app.planRepository().summary(enrollment)?.statuses }) {
            app.planRepository().summary(enrollment)?.statuses?.get(day) == status
        }

    private fun describe(b: MediaBrowser) =
        "state=${b.playbackState} items=${(0 until b.mediaItemCount).map { b.getMediaItemAt(it).mediaId }} current=${b.currentMediaItemIndex} error=${b.playerError}"

    @Test
    fun browsePlayAndCompleteDays() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val b = MediaBrowser.Builder(context, token).buildAsync().await()
            browser = b
            suspend fun children(id: String) = b.getChildren(id, 0, Int.MAX_VALUE, null).await().value!!

            val e = enrollment
            assertTrue("plan/$e/today" in children("today").map { it.mediaId })
            assertTrue("plan/$e" in children("plan").map { it.mediaId })
            assertEquals(listOf("plan/$e/today", "plan/$e/days/1-3"), children("plan/$e").map { it.mediaId })
            val days = children("plan/$e/days/1-3")
            assertEquals(listOf("plan/$e/day/1", "plan/$e/day/2", "plan/$e/day/3"), days.map { it.mediaId })
            assertEquals("Genesis 1–2", days[0].mediaMetadata.subtitle.toString())

            // Play Today (day 1) by ID only, as Auto does: GEN 1, GEN 2 as plan items.
            b.setMediaItems(listOf(MediaItem.Builder().setMediaId("plan/$e/today").build()))
            b.prepare()
            b.play()
            until("day 1 queue", 15_000, { describe(b) }) { b.mediaItemCount == 2 && b.getMediaItemAt(0).mediaId == "plan/$e/day/1/0" }
            assertEquals("plan/$e/day/1/1", b.getMediaItemAt(1).mediaId)
            assertEquals("Genesis 1", b.getMediaItemAt(0).mediaMetadata.title.toString())
            until("day 1 to end", state = { describe(b) }) { b.playbackState == Player.STATE_ENDED }
        }
        waitForDay(1, DayStatus.COMPLETE)

        // Caught up on the calendar, yet day 2 is current and playable now, labelled "(up next)".
        val summary = app.planRepository().summary(enrollment)!!
        assertEquals(2, summary.position.currentDay)
        assertTrue(summary.position.upNext)

        app.settingsRepository().setContinueToNextDay(true)
        withContext(Dispatchers.Main) {
            val b = browser!!
            val today = b.getItem("plan/$enrollment/today").await().value!!
            assertEquals("Test Plan · Day 2 (up next)", today.mediaMetadata.title.toString())

            // Day 2 has one item; with "continue to next day" on, day 3 is appended when it starts.
            b.setMediaItems(listOf(MediaItem.Builder().setMediaId("plan/$enrollment/today").build()))
            b.prepare()
            b.play()
            until("day 3 appended", 15_000, { describe(b) }) { b.mediaItemCount == 2 }
            assertEquals(listOf("plan/$enrollment/day/2/0", "plan/$enrollment/day/3/0"), (0 until 2).map { b.getMediaItemAt(it).mediaId })
            until("days 2-3 to end", state = { describe(b) }) { b.playbackState == Player.STATE_ENDED }
        }
        waitForDay(3, DayStatus.COMPLETE)
        assertEquals(DayStatus.COMPLETE, app.planRepository().summary(enrollment)!!.statuses[2])
        until("FINISHED", 10_000, { app.planRepository().summary(enrollment)?.enrollment?.state }) {
            app.planRepository().summary(enrollment)?.enrollment?.state == EnrollmentState.FINISHED
        }
    }
}
