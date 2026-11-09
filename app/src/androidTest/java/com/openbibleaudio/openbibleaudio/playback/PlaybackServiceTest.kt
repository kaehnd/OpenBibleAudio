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
import com.openbibleaudio.openbibleaudio.DebugEntryPoint
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import com.openbibleaudio.openbibleaudio.library.ImportPreview
import com.openbibleaudio.openbibleaudio.library.MiniPack
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * End to end on a device (spec 0002 §10.2): import the mini pack through ImportController and the
 * real ImportWorker, then browse and play it through a MediaBrowser connected to PlaybackService.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = EntryPointAccessors.fromApplication(context, DebugEntryPoint::class.java)
    private val versionId = "test-mini"
    private val chapters = listOf(
        ChapterRef(Book.GENESIS, 1), ChapterRef(Book.GENESIS, 2), ChapterRef(Book.GENESIS, 3),
        ChapterRef(Book.PSALMS, 1), ChapterRef(Book.JUDE, 1), ChapterRef(Book.REVELATION, 22),
    )
    private lateinit var packDir: File
    private var browser: MediaBrowser? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        app.importController().delete(versionId)
        packDir = MiniPack.create(File(context.cacheDir, "mini-pack"), chapters, versionId = versionId)
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        withContext(Dispatchers.Main) { browser?.release() }
        app.importController().delete(versionId)
        packDir.deleteRecursively()
    }

    @Test
    fun importBrowseAndPlay() = runBlocking<Unit> {
        // Import through the controller and WorkManager, as the Library screen does.
        val imports = app.importController()
        val preview = imports.inspect(Uri.fromFile(packDir))
        assertIs<ImportPreview.Ready>(preview, "$preview")
        imports.start(preview, move = false)
        withTimeout(60_000) {
            while (app.libraryRepository().version(versionId)?.state != VersionState.READY) delay(200)
        }
        assertTrue(app.libraryRepository().snapshot.first().has(versionId, ChapterRef(Book.GENESIS, 2)))

        withContext(Dispatchers.Main) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val b = MediaBrowser.Builder(context, token).buildAsync().await()
            browser = b

            suspend fun children(id: String) = b.getChildren(id, 0, Int.MAX_VALUE, null).await().value!!.map { it.mediaId }

            assertEquals("root", b.getLibraryRoot(null).await().value!!.mediaId)
            assertEquals(listOf("bible"), children("root"))
            // Only one version might be imported on this device; otherwise version nodes come first.
            val bible = children("bible")
            val ot = if ("bible/$versionId" in bible) children("bible/$versionId") else bible
            assertTrue("bible/$versionId/OT" in ot && "bible/$versionId/NT" in ot, "$ot")
            assertEquals(listOf("bible/$versionId/GEN", "bible/$versionId/PSA"), children("bible/$versionId/OT"))
            assertEquals(listOf("bible/$versionId/GEN/1", "bible/$versionId/GEN/2", "bible/$versionId/GEN/3"), children("bible/$versionId/GEN"))
            val item = b.getItem("bible/$versionId/GEN/2").await().value!!
            assertEquals("Genesis 2", item.mediaMetadata.title.toString())

            // Play with only the chapter ID (as Auto does); the service expands it to the rest of the book.
            b.setMediaItems(listOf(MediaItem.Builder().setMediaId("bible/$versionId/GEN/1").build()))
            b.prepare()
            b.play()
            // The controller shows the one requested item as a placeholder until the service
            // answers with the expanded queue (GEN 1-3).
            withTimeout(15_000) { while (b.mediaItemCount != 3) delay(50) }
            assertEquals("bible/$versionId/GEN/3", b.getMediaItemAt(2).mediaId)
            withTimeout(20_000) {
                while (b.currentMediaItem?.mediaId != "bible/$versionId/GEN/2") delay(100)
            }
            assertEquals("Genesis 2", b.mediaMetadata.title.toString())
            b.stop()
            b.clearMediaItems()
            assertEquals(Player.STATE_IDLE, b.playbackState)
        }
    }
}
