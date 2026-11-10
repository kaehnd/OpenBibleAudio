package com.openbibleaudio.openbibleaudio.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.podcast.MatchSource
import com.openbibleaudio.openbibleaudio.data.db.DayStatusRow
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentDayEntity
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentItemCompletionEntity
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import com.openbibleaudio.openbibleaudio.data.db.PlanEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastFeedEntity
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plan and podcast DAOs against an in-memory Room DB (spec 0003 §5). */
@RunWith(AndroidJUnit4::class)
class PlanDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OpenBibleAudioDatabase
    private val plans get() = db.planDao()
    private val podcasts get() = db.podcastDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OpenBibleAudioDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun enroll(version: Int = 1): Long {
        plans.upsertPlan(PlanEntity("tbr", version, "The Bible Recap", "{}", 1))
        return plans.insertEnrollment(
            EnrollmentEntity(
                planId = "tbr", planVersion = version, versionId = null, startDate = "2026-11-08", podcastCycle = 7,
                downloadMode = DownloadMode.ROLLING, state = EnrollmentState.ACTIVE, createdAt = 1,
            )
        )
    }

    @Test
    fun latestPlanVersionsAndUnusedCleanup() = runBlocking {
        val e = enroll(version = 1)
        plans.upsertPlan(PlanEntity("tbr", 2, "The Bible Recap", "{}", 2))
        assertEquals(listOf(2), plans.observeLatestPlans().first().map { it.version })

        assertEquals(0, plans.deletePlanIfUnused("tbr", 1)) // still enrolled
        assertEquals(1, plans.deletePlanIfUnused("tbr", 2))
        plans.deleteEnrollment(e)
        assertEquals(1, plans.deletePlanIfUnused("tbr", 1))
    }

    @Test
    fun completionsAreRecordedOnceAndCascade() = runBlocking {
        val e = enroll()
        plans.insertCompletion(EnrollmentItemCompletionEntity(e, 1, "ch/GEN/1", 10))
        plans.insertCompletion(EnrollmentItemCompletionEntity(e, 1, "ch/GEN/1", 20))
        plans.insertCompletion(EnrollmentItemCompletionEntity(e, 1, "ep/0123456789abcdef", 30))
        assertEquals(setOf("ch/GEN/1", "ep/0123456789abcdef"), plans.completedItems(e, 1).toSet())

        plans.upsertDays(listOf(EnrollmentDayEntity(e, 1, DayStatus.COMPLETE, 30), EnrollmentDayEntity(e, 2, DayStatus.SKIPPED, null)))
        assertEquals(setOf(DayStatusRow(1, DayStatus.COMPLETE), DayStatusRow(2, DayStatus.SKIPPED)), plans.dayStatuses(e).toSet())

        plans.resetDay(e, 1)
        assertEquals(DayStatus.PENDING, plans.day(e, 1)?.status)
        assertEquals(emptyList(), plans.completedItems(e, 1))

        plans.insertCompletion(EnrollmentItemCompletionEntity(e, 2, "ch/EXO/1", 40))
        plans.deleteEnrollment(e)
        assertEquals(emptyList(), plans.dayStatuses(e))
        assertEquals(emptyList(), plans.completedItems(e, 2))
    }

    @Test
    fun episodeForDayMatchesCycleIncludingNone() = runBlocking {
        podcasts.upsertFeed(PodcastFeedEntity("0123456789ab", "https://x.example/feed", null, null, null, null, null))
        fun ep(hash: String, cycle: Int?, day: Int?, pub: Long) = PodcastEpisodeEntity(
            feedId = "0123456789ab", guidHash = hash, guid = hash, title = "Day $day", pubDate = pub,
            enclosureUrl = "https://cdn/$hash.mp3", enclosureLength = null, durationMs = null, episodeType = "full",
            cycle = cycle, matchedDay = day, matchSource = MatchSource.TITLE, inFeed = true,
        )
        podcasts.upsertEpisodes(
            listOf(ep("a000000000000001", 7, 1, 100), ep("a000000000000002", 8, 1, 200), ep("a000000000000003", null, 1, 300))
        )
        assertEquals("a000000000000001", podcasts.episodeForDay("0123456789ab", 7, 1)?.guidHash)
        assertEquals("a000000000000003", podcasts.episodeForDay("0123456789ab", null, 1)?.guidHash)
        assertNull(podcasts.episodeForDay("0123456789ab", 7, 2))

        podcasts.setDownload("0123456789ab", "a000000000000001", DownloadState.DOWNLOADED, "podcasts/x.mp3", 42, null)
        podcasts.setPosition("0123456789ab", "a000000000000001", 5_000, 9)
        val row = podcasts.episode("0123456789ab", "a000000000000001")!!
        assertEquals(DownloadState.DOWNLOADED, row.downloadState)
        assertEquals(5_000, row.positionMs)
        assertEquals(MatchSource.TITLE, row.matchSource)
    }
}
