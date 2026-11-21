package com.openbibleaudio.openbibleaudio.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.importer.fixtures.Mp3Fixtures
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.core.model.plan.EpisodeMatch
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PlanPodcast
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.DebugEntryPoint
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastFeedEntity
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A plan day with a downloaded episode, end to end on a device (spec 0003 §10.2): the queue is
 * GEN 1, GEN 2, then the episode as a podcast item, and playing it through completes the day and
 * marks the episode played. Also: starting a partly played episode resumes 2 s before where it stopped.
 *
 * The episode row and file are seeded directly (as a finished download would leave them), so no
 * network is involved. Fetching and downloading are covered by the JVM tests with MockWebServer.
 */
@RunWith(AndroidJUnit4::class)
class PlanEpisodePlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java)
    private val versionId = "test-episode-mini"
    private val chapters = listOf(ChapterRef(Book.GENESIS, 1), ChapterRef(Book.GENESIS, 2), ChapterRef(Book.GENESIS, 3))
    private val feedUrl = "https://feed.example.com/episode-test.xml"
    private val feedId = PodcastIds.feedId(feedUrl)
    private val guidHash = PodcastIds.guidHash("episode-test/day-1")
    private val plan = PlanDefinition(
        1, "test-episode-plan", 1, "Episode Plan",
        days = listOf(PlanDay(1, listOf(Reading(Book.GENESIS, 1, 2))), PlanDay(2, listOf(Reading(Book.GENESIS, 3)))),
        podcast = PlanPodcast(feedUrl, match = EpisodeMatch(titleRegex = "^Day (\\d+)")),
    )
    private lateinit var packDir: File
    private var enrollment = 0L
    private var browser: MediaBrowser? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        app.importController().delete(versionId)
        packDir = MiniPack.create(File(context.cacheDir, "episode-mini-pack"), chapters, versionId = versionId)
        val imports = app.importController()
        val preview = imports.inspect(Uri.fromFile(packDir))
        assertIs<ImportPreview.Ready>(preview, "$preview")
        imports.start(preview, move = false)
        withTimeout(60_000) {
            while (app.libraryRepository().version(versionId)?.state != VersionState.READY) delay(200)
        }
        val imported = app.planRepository().importPlan(PlanCodec.encode(plan))
        assertTrue(imported is PlanImportResult.Imported || imported is PlanImportResult.AlreadyImported, "$imported")
        // OFF: nothing is fetched or downloaded during the test.
        enrollment = app.planRepository().enroll(plan.id, plan.version, versionId = versionId, downloadMode = DownloadMode.OFF)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { browser?.release() }
        app.planRepository().unenroll(enrollment)
        app.podcastStorage().delete(feedId, guidHash)
        app.importController().delete(versionId)
        packDir.deleteRecursively()
    }

    /** Seeds the episode as a finished download, ~[seconds] s of silence, at [positionMs]. */
    private suspend fun seedEpisode(seconds: Int, positionMs: Long = 0) {
        val file = app.podcastStorage().file(feedId, guidHash)
        file.parentFile!!.mkdirs()
        file.writeBytes(Mp3Fixtures.cbr(seconds * 38))
        val dao = app.podcastDao()
        dao.upsertFeed(PodcastFeedEntity(feedId, feedUrl, "The Test Recap", null, null, null, null))
        dao.upsertEpisodes(
            listOf(
                PodcastEpisodeEntity(
                    feedId, guidHash, "episode-test/day-1", "Day 1 (Genesis 1-2)", 0, "https://cdn.example.com/1.mp3",
                    file.length(), null, "full", cycle = null, matchedDay = 1, matchSource = null, inFeed = true,
                ),
            ),
        )
        dao.setDownloaded(feedId, guidHash, file.path, file.length(), null)
        if (positionMs > 0) dao.setPosition(feedId, guidHash, positionMs, 0)
    }

    private suspend fun until(what: String, timeoutMs: Long = 30_000, state: suspend () -> Any?, done: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what; saw ${state()}")
            delay(100)
        }
    }

    private suspend fun connect(): MediaBrowser = withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        MediaBrowser.Builder(context, token).buildAsync().await().also { browser = it }
    }

    @Test
    fun theDaysQueueEndsWithTheDownloadedEpisode() = runBlocking<Unit> {
        seedEpisode(seconds = 1)
        val b = connect()
        withContext(Dispatchers.Main) {
            val today = b.getItem("plan/$enrollment/today").await().value!!
            assertTrue(today.mediaMetadata.subtitle.toString().endsWith("Episode ✓"), "${today.mediaMetadata.subtitle}")

            b.setMediaItems(listOf(MediaItem.Builder().setMediaId("plan/$enrollment/today").build()))
            b.prepare()
            b.play()
            until("day 1 queue", 15_000, { (0 until b.mediaItemCount).map { b.getMediaItemAt(it).mediaId } }) { b.mediaItemCount == 3 }
            assertEquals(
                listOf("plan/$enrollment/day/1/0", "plan/$enrollment/day/1/1", "plan/$enrollment/day/1/2"),
                (0 until 3).map { b.getMediaItemAt(it).mediaId },
            )
            val episode = b.getMediaItemAt(2).mediaMetadata
            assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, episode.mediaType)
            assertEquals("Day 1 · The Test Recap", episode.title.toString())
            assertEquals("Day 1 (Genesis 1-2)", episode.subtitle.toString())
            until("day 1 to end", state = { b.playbackState }) { b.playbackState == Player.STATE_ENDED }
        }
        until("day 1 COMPLETE", state = { app.planRepository().summary(enrollment)?.statuses }) {
            app.planRepository().summary(enrollment)?.statuses?.get(1) == DayStatus.COMPLETE
        }
        until("episode played", 10_000, { app.podcastDao().episode(feedId, guidHash) }) {
            app.podcastDao().episode(feedId, guidHash)?.completedAt != null
        }
    }

    @Test
    fun aPartlyPlayedEpisodeResumesTwoSecondsBack() = runBlocking<Unit> {
        seedEpisode(seconds = 20, positionMs = 10_000)
        val b = connect()
        withContext(Dispatchers.Main) {
            // The service's player outlives each test; make sure it doesn't start playing, so the
            // position read below is where it started.
            b.pause()
            // Start on the episode itself (as "Continue" or the day list would).
            b.setMediaItems(listOf(MediaItem.Builder().setMediaId("plan/$enrollment/day/1/2").build()))
            b.prepare()
            until("episode ready", 15_000, { b.playbackState }) { b.playbackState == Player.STATE_READY }
            assertEquals("plan/$enrollment/day/1/2", b.currentMediaItem?.mediaId)
            val position = b.currentPosition
            assertTrue(position in 7_500..8_500, "started at $position ms")
        }
        assertNotNull(app.podcastDao().episode(feedId, guidHash))
    }
}
