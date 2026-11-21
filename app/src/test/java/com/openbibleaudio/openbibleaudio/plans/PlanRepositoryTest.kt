package com.openbibleaudio.openbibleaudio.plans

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PlanRepository against an in-memory Room DB with a settable clock (spec 0003 §6). */
@RunWith(AndroidJUnit4::class)
class PlanRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OpenBibleAudioDatabase
    private val start = LocalDate.of(2026, 11, 8)
    private var today = start
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant = today.atTime(12, 0).toInstant(ZoneOffset.UTC)
    }
    private lateinit var repo: PlanRepository

    private val small = PlanDefinition(
        1, "small", 1, "Small plan",
        days = listOf(
            PlanDay(1, listOf(Reading(Book.GENESIS, 1, 2))),
            PlanDay(2, listOf(Reading(Book.GENESIS, 3))),
            PlanDay(3, listOf(Reading(Book.GENESIS, 4))),
        ),
    )
    private val everything = generateSequence(ChapterRef(Book.GENESIS, 1)) { it.next() }.toSet()
    private val noEpisode = EpisodeAvailability.Unavailable(EpisodeUnavailable.NO_PODCAST)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OpenBibleAudioDatabase::class.java).allowMainThreadQueries().build()
        repo = PlanRepository(db.planDao(), clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun importSmall(plan: PlanDefinition = small) = repo.importPlan(PlanCodec.encode(plan))

    private suspend fun enrollSmall(): Long {
        importSmall()
        return repo.enroll("small", 1, startDate = start)
    }

    private fun queue(e: Long, day: Int, episode: EpisodeAvailability = noEpisode) =
        PlanQueueBuilder.build(e, small, day, everything, episode)!!

    private suspend fun finishDay(e: Long, day: Int) {
        val q = queue(e, day)
        q.entries.forEach { repo.recordItemCompleted(q, it.itemKey) }
    }

    @Test
    fun importsTheCommittedBibleRecapPlan() = runBlocking<Unit> {
        val file = File("../plans/bible-recap-chronological.json")
        val imported = assertIs<PlanImportResult.Imported>(repo.importPlan(file.inputStream()))
        assertNull(imported.replacesVersion)
        assertTrue(imported.notes.isEmpty()) // reads the whole Bible once, so no coverage note
        assertEquals(365, repo.plan(imported.id, imported.version)?.dayCount)

        assertIs<PlanImportResult.AlreadyImported>(repo.importPlan(file.inputStream()))
    }

    @Test
    fun newerVersionIsStoredNextToTheOld() = runBlocking<Unit> {
        val e = enrollSmall()
        val r = assertIs<PlanImportResult.Imported>(importSmall(small.copy(version = 2, name = "Small plan v2")))
        assertEquals(1, r.replacesVersion)
        assertEquals(listOf(2), repo.plans.first().map { it.version })
        // The enrollment keeps its version (open question 4).
        assertEquals(1, repo.summary(e)?.enrollment?.planVersion)
        assertEquals("Small plan", repo.summary(e)?.plan?.name)
    }

    @Test
    fun partialPlansImportWithACoverageNote() = runBlocking<Unit> {
        val r = assertIs<PlanImportResult.Imported>(importSmall())
        assertEquals(listOf(IssueCode.PARTIAL_COVERAGE), r.notes.map { it.code })
    }

    @Test
    fun badFilesAreRejected() = runBlocking<Unit> {
        assertIs<PlanImportResult.Unreadable>(repo.importPlan("not json"))
        assertIs<PlanImportResult.Unreadable>(repo.importPlan(ByteArrayInputStream(ByteArray(5_000_000))))

        val manyBad = small.copy(days = (1..30).map { PlanDay(it, listOf(Reading(Book.JUDE, 2))) })
        val invalid = assertIs<PlanImportResult.Invalid>(importSmall(manyBad))
        assertEquals(30, invalid.errorCount)
        assertEquals(PlanImportResult.MAX_SHOWN, invalid.shown.size)
        assertEquals(emptyList(), repo.plans.first())
    }

    @Test
    fun completingItemsAdvancesTheDayAndCaughtUpKeepsGoing() = runBlocking<Unit> {
        val e = enrollSmall()
        val q = queue(e, 1)
        assertEquals(DayStatus.IN_PROGRESS, repo.recordItemCompleted(q, "ch/GEN/1"))
        assertEquals(DayStatus.IN_PROGRESS, repo.recordItemCompleted(q, "ch/GEN/1")) // a repeat is harmless
        assertEquals(DayStatus.COMPLETE, repo.recordItemCompleted(q, "ch/GEN/2"))

        // Same calendar day: day 2 is current straight away, labelled "(up next)".
        val p = repo.summary(e)!!.position
        assertEquals(2, p.currentDay)
        assertTrue(p.upNext)
        assertEquals(3, repo.nextDayAfter(e, 2))
    }

    /** Deleting a plan while its day plays used to crash on a foreign key (found by VoiceSearchPlaybackTest). */
    @Test
    fun completingAnItemOfADeletedEnrollmentRecordsNothing() = runBlocking<Unit> {
        val e = enrollSmall()
        val q = queue(e, 1)
        repo.unenroll(e)
        assertNull(repo.recordItemCompleted(q, "ch/GEN/1"))
        assertNull(repo.summary(e))
    }

    @Test
    fun episodeBlocksCompletionOnlyWhenItWasInTheQueue() = runBlocking<Unit> {
        val e = enrollSmall()
        val withEpisode = queue(e, 1, EpisodeAvailability.Local("0123456789ab", "0123456789abcdef", "file:///e.mp3"))
        repo.recordItemCompleted(withEpisode, "ch/GEN/1")
        assertEquals(DayStatus.IN_PROGRESS, repo.recordItemCompleted(withEpisode, "ch/GEN/2"))
        assertEquals(DayStatus.COMPLETE, repo.recordItemCompleted(withEpisode, "ep/0123456789abcdef"))

        val missing = queue(e, 2, EpisodeAvailability.Unavailable(EpisodeUnavailable.NOT_DOWNLOADED))
        assertEquals(DayStatus.COMPLETE, repo.recordItemCompleted(missing, "ch/GEN/3"))
    }

    @Test
    fun finishingEveryDayFinishesTheEnrollmentAndReopeningUndoesIt() = runBlocking<Unit> {
        val e = enrollSmall()
        (1..3).forEach { finishDay(e, it) }
        val done = repo.summary(e)!!
        assertEquals(EnrollmentState.FINISHED, done.enrollment.state)
        assertNull(done.position.currentDay)

        repo.markNotDone(e, 2)
        val reopened = repo.summary(e)!!
        assertEquals(EnrollmentState.ACTIVE, reopened.enrollment.state)
        assertEquals(2, reopened.position.currentDay)

        repo.restart(e)
        assertEquals(1, repo.summary(e)!!.position.currentDay)
    }

    @Test
    fun manualCompleteStandsAndSkipToToday() = runBlocking<Unit> {
        val e = enrollSmall()
        repo.markComplete(e, 1)
        repo.recordItemCompleted(queue(e, 1), "ch/GEN/1") // computes IN_PROGRESS; the manual COMPLETE stands
        assertEquals(DayStatus.COMPLETE, repo.summary(e)!!.statuses[1])

        today = start.plusDays(2) // calendar day 3
        assertEquals(1, repo.skipToToday(e))
        val s = repo.summary(e)!!
        assertEquals(DayStatus.SKIPPED, s.statuses[2])
        assertEquals(3, s.position.currentDay)
        assertEquals(0, s.position.behind)
    }

    @Test
    fun enrollmentsFlowAndUnenroll() = runBlocking<Unit> {
        val a = enrollSmall()
        val b = repo.enroll("small", 1, startDate = start) // a second listener on the same plan
        finishDay(a, 1)
        val summaries = repo.enrollments.first()
        assertEquals(listOf(a, b), summaries.map { it.enrollment.id })
        assertEquals(listOf(2, 1), summaries.map { it.position.currentDay })

        repo.unenroll(a)
        assertEquals(1, repo.plans.first().size) // still used by b
        repo.unenroll(b)
        assertEquals(emptyList(), repo.plans.first())
    }
}
