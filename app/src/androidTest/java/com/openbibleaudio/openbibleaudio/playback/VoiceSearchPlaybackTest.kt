package com.openbibleaudio.openbibleaudio.playback

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.DebugEntryPoint
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import com.openbibleaudio.openbibleaudio.library.ImportPreview
import com.openbibleaudio.openbibleaudio.library.MiniPack
import com.openbibleaudio.openbibleaudio.plans.PlanImportResult
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Voice requests as Gemini / Assistant send them in Android Auto: a *legacy* (platform)
 * MediaBrowser connects, takes the session token and calls `playFromSearch` (spec 0002 §7.7,
 * spec 0003 §9.2, DHU checklist §10.3 #8). Playback must start, or the assistant times out.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSearchPlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java)
    private val versionId = "test-voice-mini"
    private val chapters = listOf(ChapterRef(Book.GENESIS, 1), ChapterRef(Book.GENESIS, 2), ChapterRef(Book.PSALMS, 23))
    private val plan = PlanDefinition(
        1, "test-voice-plan", 1, "Voice Test Plan",
        days = listOf(PlanDay(1, listOf(Reading(Book.GENESIS, 1, 2)))),
    )
    private lateinit var packDir: File
    private var enrollment = 0L
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        app.importController().delete(versionId)
        packDir = MiniPack.create(File(context.cacheDir, "voice-mini-pack"), chapters, versionId = versionId)
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
        enrollment = plans.enroll("test-voice-plan", 1, versionId = versionId)

        withContext(Dispatchers.Main) {
            val connected = CompletableDeferred<Unit>()
            val b = MediaBrowser(
                context, ComponentName(context, PlaybackService::class.java),
                object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { connected.complete(Unit) }
                    override fun onConnectionFailed() { connected.completeExceptionally(AssertionError("connection failed")) }
                },
                null,
            )
            browser = b
            b.connect()
            withTimeout(15_000) { connected.await() }
            controller = MediaController(context, b.sessionToken)
        }
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            controller?.transportControls?.stop()
            browser?.disconnect()
        }
        app.planRepository().unenroll(enrollment)
        app.importController().delete(versionId)
        packDir.deleteRecursively()
    }

    private fun describe(c: MediaController) =
        "state=${c.playbackState?.state} error=${c.playbackState?.errorMessage} title=${c.metadata?.description?.title} queue=${c.queue?.map { it.description.mediaId }}"

    /**
     * Calls playFromSearch and waits for PLAYING, as the assistant does (it gives up after ~10 s).
     * States are recorded by a callback, not polled: the mini pack's clips are so short that a day
     * can play to the end between two polls.
     */
    private suspend fun playFromSearch(query: String): MediaController = withContext(Dispatchers.Main) {
        val c = controller!!
        val playing = CompletableDeferred<Unit>()
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (state?.state == PlaybackState.STATE_PLAYING) playing.complete(Unit)
            }
        }
        c.registerCallback(callback)
        try {
            c.transportControls.playFromSearch(query, Bundle())
            withTimeoutOrNull(10_000) { playing.await() }
                ?: throw AssertionError("\"$query\" didn't start playing within 10 s; saw ${describe(c)}")
        } finally {
            c.unregisterCallback(callback)
        }
        c
    }

    @Test
    fun todaysReadingPlaysThePlanDay() = runBlocking<Unit> {
        val c = playFromSearch("today's reading")
        withContext(Dispatchers.Main) {
            assertTrue(c.queue.orEmpty().any { it.description.mediaId == "plan/$enrollment/day/1/0" }, describe(c))
        }
    }

    @Test
    fun aPlanNamePlaysThatPlan() = runBlocking<Unit> {
        val c = playFromSearch("voice test plan")
        withContext(Dispatchers.Main) {
            assertTrue(c.queue.orEmpty().any { it.description.mediaId?.startsWith("plan/$enrollment/") == true }, describe(c))
        }
    }

    @Test
    fun anEmptyQueryResumes() = runBlocking<Unit> {
        // "Play Open Bible Audio": the app name is stripped, leaving an empty query.
        playFromSearch("")
    }

    /** Gemini passes the app's name through as the query (seen on the DHU, 2026-11-21). */
    @Test
    fun theAppsNameAloneResumes() = runBlocking<Unit> {
        playFromSearch("Open Bible audio")
    }

    @Test
    fun todaysReadingOnTheAppPlaysThePlanDay() = runBlocking<Unit> {
        val c = playFromSearch("today's reading on Open Bible Audio")
        withContext(Dispatchers.Main) {
            assertTrue(c.queue.orEmpty().any { it.description.mediaId == "plan/$enrollment/day/1/0" }, describe(c))
        }
    }

    @Test
    fun aReferencePlaysThatChapter() = runBlocking<Unit> {
        val c = playFromSearch("psalm 23")
        withContext(Dispatchers.Main) {
            assertTrue(c.metadata?.description?.title.toString().contains("23"), describe(c))
        }
    }
}
