package com.openbibleaudio.openbibleaudio.podcasts

import android.content.Context
import android.util.Xml
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.ManualPinEntity
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import com.openbibleaudio.openbibleaudio.data.db.PlanEntity
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Refresh, staleness, snapshot safety and episode lookup (spec 0003 §7) with Room + MockWebServer. */
@RunWith(AndroidJUnit4::class)
class PodcastRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var db: OpenBibleAudioDatabase
    private lateinit var plans: PlanRepository
    private lateinit var podcasts: PodcastRepository
    private var now = Instant.parse("2026-11-20T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val plan by lazy {
        TbrShapedFeed.plan.copy(podcast = TbrShapedFeed.podcast.copy(feedUrl = server.url("/feed.xml").toString()))
    }
    private val feedId by lazy { PodcastIds.feedId(plan.podcast!!.feedUrl) }

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, OpenBibleAudioDatabase::class.java).allowMainThreadQueries().build()
        plans = PlanRepository(db.planDao(), clock)
        val fetcher = FeedFetcher(OkHttpClient(), "OpenBibleAudio/test") { Xml.newPullParser() }
        podcasts = PodcastRepository(db.podcastDao(), db.planDao(), plans, fetcher, clock)
        // Stored directly: the validator (rightly) wants https, and MockWebServer is plain http.
        runBlocking { db.planDao().upsertPlan(PlanEntity(plan.id, plan.version, plan.name, PlanCodec.encode(plan), 0)) }
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun feedResponse() = MockResponse().setBody(TbrShapedFeed.xml).setHeader("ETag", "\"v1\"")

    @Test
    fun refreshStoresTheSnapshotAndSkipsAFreshFeed() = runBlocking {
        server.enqueue(feedResponse())
        assertIs<FeedRefreshResult.Updated>(podcasts.refresh(plan))
        val feed = assertNotNull(db.podcastDao().feed(feedId))
        assertEquals("The Recap (fixture)", feed.title)
        assertEquals("\"v1\"", feed.etag)
        assertEquals(listOf(8, 7, 6), podcasts.cycles(plan).map { it.key })

        // Within 12 h nothing is sent.
        now = now.plus(Duration.ofHours(11))
        assertIs<FeedRefreshResult.Fresh>(podcasts.refresh(plan))
        assertEquals(1, server.requestCount)

        // After that, a conditional GET.
        now = now.plus(Duration.ofHours(2))
        server.enqueue(MockResponse().setResponseCode(304))
        assertIs<FeedRefreshResult.NotModified>(podcasts.refresh(plan))
        server.takeRequest()
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun refreshNeverOverwritesDownloadOrPlaybackState() = runBlocking {
        server.enqueue(feedResponse())
        podcasts.refresh(plan)
        val hash = PodcastIds.guidHash(TbrShapedFeed.guid(7, 1))
        db.podcastDao().setDownload(feedId, hash, DownloadState.DOWNLOADED, "/files/podcasts/x.mp3", 7_000_000, null)
        db.podcastDao().setPosition(feedId, hash, 61_000, now.toEpochMilli())

        server.enqueue(feedResponse())
        podcasts.refresh(plan, maxAge = Duration.ZERO)
        val row = assertNotNull(db.podcastDao().episode(feedId, hash))
        assertEquals(DownloadState.DOWNLOADED, row.downloadState)
        assertEquals("/files/podcasts/x.mp3", row.localPath)
        assertEquals(61_000, row.positionMs)
        assertEquals(1, row.matchedDay)
    }

    @Test
    fun aFailedRefreshKeepsTheSnapshotAndRecordsTheError() = runBlocking {
        server.enqueue(feedResponse())
        podcasts.refresh(plan)
        server.enqueue(MockResponse().setResponseCode(500))
        val r = assertIs<FeedRefreshResult.Failed>(podcasts.refresh(plan, maxAge = Duration.ZERO))
        assertEquals(true, r.retryable)
        assertEquals(3, podcasts.cycles(plan).size)
        assertNotNull(db.podcastDao().feed(feedId)?.lastError)

        // A feed with an error isn't "fresh": the next refresh tries again.
        server.enqueue(MockResponse().setResponseCode(304))
        assertIs<FeedRefreshResult.NotModified>(podcasts.refresh(plan))
        assertNull(db.podcastDao().feed(feedId)?.lastError)
    }

    @Test
    fun episodeForDayUsesTheEnrollmentsCycleThenItsPins() = runBlocking {
        server.enqueue(feedResponse())
        podcasts.refresh(plan)
        val id = plans.enroll(plan.id, plan.version, podcastCycle = 7)
        val e = assertNotNull(db.planDao().enrollment(id))
        assertEquals(TbrShapedFeed.guid(7, 2), podcasts.episodeForDay(e, plan, 2)?.guid)
        assertNull(podcasts.episodeForDay(e.copy(podcastCycle = 8), plan, 4)) // Year 8 has days 1–3 only

        val bonus = PodcastIds.guidHash("recap.example.com/bonus-jan-8")
        db.planDao().upsertPin(ManualPinEntity(id, 2, feedId, bonus))
        assertEquals("recap.example.com/bonus-jan-8", podcasts.episodeForDay(e, plan, 2)?.guid)
    }

    @Test
    fun anEnrollmentMadeOfflineGetsTheDefaultCycleLater() = runBlocking {
        val id = plans.enroll(plan.id, plan.version, podcastCycle = null)
        server.enqueue(feedResponse())
        assertEquals(true, podcasts.refreshActive())
        assertEquals(7, db.planDao().enrollment(id)?.podcastCycle)

        // A chosen cycle is left alone.
        val chosen = plans.enroll(plan.id, plan.version, podcastCycle = 6)
        podcasts.refreshActive()
        assertEquals(6, db.planDao().enrollment(chosen)?.podcastCycle)
    }

    @Test
    fun refreshActiveReportsRetryableFailures() = runBlocking {
        plans.enroll(plan.id, plan.version, podcastCycle = 7)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(false, podcasts.refreshActive())
    }
}
