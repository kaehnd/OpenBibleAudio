package com.openbibleaudio.openbibleaudio.podcasts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastFeedEntity
import com.openbibleaudio.openbibleaudio.library.DurationProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The download protocol (spec 0003 §8.2, §10.2) against MockWebServer, Room and a temp folder. */
@RunWith(AndroidJUnit4::class)
class EpisodeDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private lateinit var db: OpenBibleAudioDatabase
    private lateinit var storage: PodcastStorage
    private var free = Long.MAX_VALUE / 2
    private lateinit var downloader: EpisodeDownloader

    private val feedId = PodcastIds.feedId("https://feed.example.com/feed.xml")
    private val audio = ByteArray(300_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, OpenBibleAudioDatabase::class.java).allowMainThreadQueries().build()
        storage = PodcastStorage(tmp.root) { free }
        downloader = EpisodeDownloader(OkHttpClient(), "OpenBibleAudio/test", db.podcastDao(), storage, DurationProber { 42_000 })
        runBlocking { db.podcastDao().upsertFeed(PodcastFeedEntity(feedId, "https://feed.example.com/feed.xml", null, null, null, null, null)) }
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun episode(path: String = "/ep.mp3", length: Long? = audio.size.toLong(), durationMs: Long? = null): PodcastEpisodeEntity {
        val e = PodcastEpisodeEntity(
            feedId, PodcastIds.guidHash(path), path, "Day 1", 0, server.url(path).toString(), length, durationMs,
            "full", 7, 1, null, inFeed = true,
        )
        db.podcastDao().insertEpisodes(listOf(e))
        return e
    }

    private suspend fun row(e: PodcastEpisodeEntity) = assertNotNull(db.podcastDao().episode(feedId, e.guidHash))

    private fun body(bytes: ByteArray) = Buffer().write(bytes)

    @Test
    fun followsARedirectAndStoresTheFile() = runBlocking {
        val e = episode()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/cdn/ep.mp3")))
        server.enqueue(MockResponse().setBody(body(audio)).setHeader("ETag", "\"a\""))

        assertEquals(DownloadOutcome.Done, downloader.download(e))
        val r = row(e)
        assertEquals(DownloadState.DOWNLOADED, r.downloadState)
        assertEquals(audio.size.toLong(), r.downloadedBytes)
        assertEquals(42_000, r.durationMs) // from the prober: the feed had none
        val file = storage.file(feedId, e.guidHash)
        assertEquals(file.path, r.localPath)
        assertTrue(file.readBytes().contentEquals(audio))
        assertFalse(storage.partFile(file).exists())
        assertFalse(storage.validatorFile(file).exists())
        assertEquals("OpenBibleAudio/test", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun theFeedsDurationWinsOverTheProbe() = runBlocking {
        val e = episode(durationMs = 468_000)
        server.enqueue(MockResponse().setBody(body(audio)))
        downloader.download(e)
        assertEquals(468_000, row(e).durationMs)
    }

    @Test
    fun aDroppedTransferIsResumedWithRangeAndIfRange() = runBlocking {
        val e = episode()
        // First attempt: headers promise the full file, then the connection drops part-way.
        server.enqueue(
            MockResponse().setBody(body(audio)).setHeader("ETag", "\"v1\"")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        assertIs<DownloadOutcome.Retry>(downloader.download(e))
        val part = storage.partFile(storage.file(feedId, e.guidHash))
        val have = part.length()
        assertTrue(have in 1 until audio.size, "kept $have bytes")
        assertEquals(DownloadState.QUEUED, row(e).downloadState)
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(206).setBody(body(audio.copyOfRange(have.toInt(), audio.size)))
                .setHeader("Content-Range", "bytes $have-${audio.size - 1}/${audio.size}"),
        )
        assertEquals(DownloadOutcome.Done, downloader.download(e))
        val resume = server.takeRequest()
        assertEquals("bytes=$have-", resume.getHeader("Range"))
        assertEquals("\"v1\"", resume.getHeader("If-Range"))
        assertTrue(storage.file(feedId, e.guidHash).readBytes().contentEquals(audio))
    }

    @Test
    fun a200ToARangeRequestRestartsTheFile() = runBlocking {
        val e = episode()
        val dest = storage.file(feedId, e.guidHash)
        dest.parentFile!!.mkdirs()
        storage.partFile(dest).writeBytes(ByteArray(1_000) { 7 }) // stale bytes from an older copy
        storage.validatorFile(dest).writeText("\"old\"")
        server.enqueue(MockResponse().setBody(body(audio)).setHeader("ETag", "\"new\""))

        assertEquals(DownloadOutcome.Done, downloader.download(e))
        assertNotNull(server.takeRequest().getHeader("Range"))
        assertTrue(dest.readBytes().contentEquals(audio))
    }

    @Test
    fun aPartWithoutAValidatorIsNotResumed() = runBlocking {
        val e = episode()
        val dest = storage.file(feedId, e.guidHash)
        dest.parentFile!!.mkdirs()
        storage.partFile(dest).writeBytes(ByteArray(1_000))
        server.enqueue(MockResponse().setBody(body(audio)))
        assertEquals(DownloadOutcome.Done, downloader.download(e))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test
    fun notFoundMarksTheEpisodeFailedForGood() = runBlocking {
        val e = episode()
        server.enqueue(MockResponse().setResponseCode(404))
        assertIs<DownloadOutcome.Gone>(downloader.download(e))
        val r = row(e)
        assertEquals(DownloadState.FAILED, r.downloadState)
        assertEquals(EpisodeDownloader.NO_LONGER_AVAILABLE, r.downloadError)
    }

    @Test
    fun serverErrorsAreRetried() = runBlocking {
        val e = episode()
        server.enqueue(MockResponse().setResponseCode(503))
        assertIs<DownloadOutcome.Retry>(downloader.download(e))
        assertEquals(DownloadState.QUEUED, row(e).downloadState)
    }

    @Test
    fun aSizeDifferentFromTheFeedIsOnlyAWarning() = runBlocking {
        val e = episode(length = 999)
        server.enqueue(MockResponse().setBody(body(audio)))
        assertEquals(DownloadOutcome.Done, downloader.download(e))
        assertEquals(audio.size.toLong(), row(e).downloadedBytes)
    }

    @Test
    fun notEnoughSpaceStopsWithoutFailingTheEpisode() = runBlocking {
        val e = episode()
        free = EpisodeDownloader.SPACE_MARGIN + 10
        server.enqueue(MockResponse().setBody(body(audio)))
        assertIs<DownloadOutcome.NoSpace>(downloader.download(e))
        val r = row(e)
        assertEquals(DownloadState.NONE, r.downloadState)
        assertEquals(EpisodeDownloader.NO_SPACE, r.downloadError)
    }

    @Test
    fun cancellingPutsTheEpisodeBackInTheQueue() = runBlocking {
        val e = episode()
        server.enqueue(MockResponse().setBody(body(audio)).setHeader("ETag", "\"v1\""))
        assertFailsWith<CancellationException> {
            downloader.download(e) { bytes, _ -> if (bytes > 0) throw CancellationException("stop") }
        }
        assertEquals(DownloadState.QUEUED, row(e).downloadState)
        assertTrue(storage.partFile(storage.file(feedId, e.guidHash)).exists())
    }
}
