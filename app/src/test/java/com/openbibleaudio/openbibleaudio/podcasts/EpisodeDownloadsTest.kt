package com.openbibleaudio.openbibleaudio.podcasts

import android.content.Context
import android.util.Xml
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.podcast.FeedParser
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import com.openbibleaudio.openbibleaudio.data.SettingsRepository
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.ManualPinEntity
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import com.openbibleaudio.openbibleaudio.data.db.PlanEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastFeedEntity
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Queue building from the DB, scheduling and retention (spec 0003 §8.1, §8.4). */
@RunWith(AndroidJUnit4::class)
class EpisodeDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OpenBibleAudioDatabase
    private lateinit var plans: PlanRepository
    private lateinit var downloads: EpisodeDownloads
    private lateinit var storage: PodcastStorage
    private val scheduled = mutableListOf<Pair<String, Boolean>>()
    private var now = Instant.parse("2026-11-20T12:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant = now
    }
    private val plan = TbrShapedFeed.plan
    private val feedId = PodcastIds.feedId(TbrShapedFeed.FEED_URL)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, OpenBibleAudioDatabase::class.java).allowMainThreadQueries().build()
        plans = PlanRepository(db.planDao(), clock)
        storage = PodcastStorage(tmp.root) { Long.MAX_VALUE / 2 }
        val settings = SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("settings.preferences_pb") })
        downloads = EpisodeDownloads(plans, db.planDao(), db.podcastDao(), storage, settings, { f, u -> scheduled += f to u }, clock)

        db.planDao().upsertPlan(PlanEntity(plan.id, plan.version, plan.name, PlanCodec.encode(plan), 0))
        val items = FeedParser.parse(TbrShapedFeed.xml.byteInputStream(), Xml.newPullParser()).items
        db.podcastDao().applyRefresh(
            PodcastFeedEntity(feedId, TbrShapedFeed.FEED_URL, null, null, null, null, null),
            FeedSnapshot.merge(feedId, plan.podcast!!, plan.dayCount, items, emptyList()),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun enroll(mode: DownloadMode, cycle: Int = 7, rolling: Int = 2) =
        plans.enroll(plan.id, plan.version, podcastCycle = cycle, downloadMode = mode, rollingDays = rolling)

    private fun hash(year: Int, day: Int, variant: String = "") = PodcastIds.guidHash(TbrShapedFeed.guid(year, day, variant))

    @Test
    fun theQueueFollowsTheEnrollmentsCycleModeAndPins() = runBlocking {
        val e = enroll(DownloadMode.ROLLING, rolling = 2)
        assertEquals(listOf(hash(7, 1), hash(7, 2)), downloads.queue(feedId).map { it.guidHash })

        db.planDao().upsertPin(ManualPinEntity(e, 2, feedId, PodcastIds.guidHash("recap.example.com/bonus-jan-8")))
        assertEquals(listOf(hash(7, 1), PodcastIds.guidHash("recap.example.com/bonus-jan-8")), downloads.queue(feedId).map { it.guidHash })

        downloads.setMode(e, DownloadMode.ALL)
        assertEquals(5, downloads.queue(feedId).size)
        downloads.setMode(e, DownloadMode.OFF)
        assertEquals(emptyList(), downloads.queue(feedId))
    }

    @Test
    fun startAllSchedulesFeedsThatDownload() = runBlocking {
        enroll(DownloadMode.OFF)
        downloads.startAll()
        assertEquals(emptyList(), scheduled)

        enroll(DownloadMode.ALL)
        downloads.startAll()
        // podcastUnmeteredOnly defaults to true.
        assertEquals(listOf(feedId to true), scheduled)
    }

    @Test
    fun retentionDeletesFinishedEpisodesAfterTheRetentionPeriod() = runBlocking {
        val e = enroll(DownloadMode.ROLLING)
        for (h in listOf(hash(7, 1), hash(7, 2))) {
            val f = storage.file(feedId, h)
            f.parentFile!!.mkdirs()
            f.writeBytes(ByteArray(10))
            db.podcastDao().setDownloaded(feedId, h, f.path, 10, null)
        }
        plans.markComplete(e, 1)
        assertEquals(0, downloads.applyRetention()) // finished just now: kept for 7 days

        now = now.plusSeconds(8L * 24 * 3600)
        assertEquals(1, downloads.applyRetention())
        val gone = db.podcastDao().episode(feedId, hash(7, 1))!!
        assertEquals(DownloadState.NONE, gone.downloadState)
        assertNull(gone.localPath)
        assertFalse(storage.file(feedId, hash(7, 1)).exists())
        assertEquals(DownloadState.DOWNLOADED, db.podcastDao().episode(feedId, hash(7, 2))!!.downloadState)

        // And a finished day's episode isn't downloaded again.
        assertFalse(hash(7, 1) in downloads.queue(feedId).map { it.guidHash })
    }
}
