package com.openbibleaudio.openbibleaudio.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.pack.PackManifestCodec
import com.openbibleaudio.openbibleaudio.data.BibleStorage
import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.data.ResumptionStore
import com.openbibleaudio.openbibleaudio.data.db.AudioFileEntity
import com.openbibleaudio.openbibleaudio.data.db.BibleVersionEntity
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PackImporter against an in-memory Room DB and a FilePackSource (spec 0002 §10.2). */
@RunWith(AndroidJUnit4::class)
class PackImporterTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OpenBibleAudioDatabase
    private lateinit var work: File
    private lateinit var storage: BibleStorage
    private lateinit var packDir: File
    private val chapters = listOf(ChapterRef(Book.GENESIS, 1), ChapterRef(Book.GENESIS, 2), ChapterRef(Book.GENESIS, 3), ChapterRef(Book.JUDE, 1))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OpenBibleAudioDatabase::class.java).allowMainThreadQueries().build()
        work = File(context.cacheDir, "importer-test-${System.nanoTime()}").apply { mkdirs() }
        storage = BibleStorage(File(work, "files"))
        packDir = MiniPack.create(File(work, "pack"), chapters)
    }

    @After
    fun tearDown() {
        db.close()
        work.deleteRecursively()
    }

    private val dao get() = db.libraryDao()

    /** What ImportController.start does, without SAF or WorkManager. */
    private fun register(move: Boolean = false, source: File = packDir): String = runBlocking {
        val text = File(source, "manifest.json").readText()
        val inspection = PackInspector.inspect(FilePackSource(source)) as PackInspection.Valid
        val m = inspection.manifest
        storage.manifest(m.bible.versionId).apply { parentFile!!.mkdirs() }.writeText(text)
        dao.upsertVersion(
            BibleVersionEntity(
                m.bible.versionId, m.bible.abbreviation, m.bible.name, m.bible.language, m.bible.versification,
                m.bible.narrator, m.bible.attribution, VersionState.IMPORTING, inspection.totalBytes, null,
                Uri.fromFile(source).toString(), move,
            ),
        )
        dao.insertFiles(inspection.files.map { AudioFileEntity(versionId = m.bible.versionId, path = it.path, sizeBytes = it.sizeBytes, sha256 = it.sha256, durationMs = it.durationMs, imported = false) })
        m.bible.versionId
    }

    private fun importer(sources: (Uri) -> PackSource = { FilePackSource(File(it.path!!)) }) =
        PackImporter(dao, storage, sources, DurationProber { 1234 }, clock = { 42 })

    @Test
    fun happyPathCopiesVerifiesAndPublishesChapters() = runBlocking<Unit> {
        val id = register()
        val progress = mutableListOf<ImportProgress>()
        assertEquals(ImportOutcome.Success, importer().run(id, verifyHashes = true, onProgress = { progress += it }))

        val version = dao.version(id)!!
        assertEquals(VersionState.READY, version.state)
        assertEquals(42L, version.importedAt)
        assertNull(version.sourceTreeUri)
        assertEquals(4, progress.last().doneFiles)
        for (ref in chapters) {
            val name = com.openbibleaudio.core.importer.fixtures.BsbNames.fileName(ref.book, ref.chapter)
            assertContentEquals(File(packDir, name).readBytes(), storage.file(id, name).readBytes())
            assertTrue(File(packDir, name).exists(), "copy mode keeps the source")
        }
        assertFalse(storage.versionDir(id).walkTopDown().any { it.name.endsWith(".part") })

        val library = LibraryRepository(dao, storage, ResumptionStore(PreferenceDataStoreFactory.create { File(work, "r.preferences_pb") }))
        val snap = library.snapshot.first()
        assertEquals(chapters, snap.chapters(id).toList())
        val source = library.chapterSource(id, ChapterRef(Book.JUDE, 1))!!
        assertEquals(0, source.startMs)
        assertTrue(source.durationMs!! > 0)
    }

    @Test
    fun chaptersStayHiddenUntilTheImportFinishes() = runBlocking<Unit> {
        register()
        val library = LibraryRepository(dao, storage, ResumptionStore(PreferenceDataStoreFactory.create { File(work, "r.preferences_pb") }))
        assertEquals(0, library.snapshot.first().versions.size)
    }

    @Test
    fun moveDeletesEachSourceAfterItsCopy() = runBlocking<Unit> {
        val id = register(move = true)
        assertEquals(ImportOutcome.Success, importer().run(id, verifyHashes = true))
        assertEquals(listOf("manifest.json"), packDir.list()!!.toList())
    }

    @Test
    fun hashMismatchFailsAndMarksVersion() = runBlocking<Unit> {
        val id = register()
        val victim = File(packDir, com.openbibleaudio.core.importer.fixtures.BsbNames.fileName(Book.GENESIS, 2))
        victim.writeBytes(victim.readBytes().also { it[200] = 0x55 })
        val outcome = importer().run(id, verifyHashes = true)
        assertIs<ImportOutcome.Failure>(outcome)
        assertTrue("SHA-256" in outcome.reason)
        assertEquals(VersionState.FAILED, dao.version(id)!!.state)
        // Without hash verification, the same file passes (size still matches).
        dao.setState(id, VersionState.IMPORTING, null)
        assertEquals(ImportOutcome.Success, importer().run(id, verifyHashes = false))
    }

    @Test
    fun sizeMismatchFails() = runBlocking<Unit> {
        val id = register()
        File(packDir, com.openbibleaudio.core.importer.fixtures.BsbNames.fileName(Book.JUDE, 1)).appendBytes(ByteArray(10))
        val outcome = importer().run(id, verifyHashes = false)
        assertIs<ImportOutcome.Failure>(outcome)
    }

    @Test
    fun resumesAfterACrashWithoutRecopyingVerifiedFiles() = runBlocking<Unit> {
        val id = register()
        val opened = mutableListOf<String>()
        var failAfter = 2
        val flaky: (Uri) -> PackSource = { uri ->
            object : PackSource by FilePackSource(File(uri.path!!)) {
                override fun open(path: String): InputStream {
                    if (failAfter-- <= 0) throw IOException("simulated crash")
                    opened += path
                    return FilePackSource(File(uri.path!!)).open(path)
                }
            }
        }
        assertIs<ImportOutcome.Retry>(importer(flaky).run(id, verifyHashes = true))
        assertEquals(2, dao.files(id).count { it.imported })
        assertEquals(VersionState.IMPORTING, dao.version(id)!!.state)

        opened.clear()
        failAfter = Int.MAX_VALUE
        assertEquals(ImportOutcome.Success, importer(flaky).run(id, verifyHashes = true))
        assertEquals(2, opened.size, "only the two remaining files are copied: $opened")
    }

    @Test
    fun cancelStopsAtAFileBoundaryAndLeavesNoPartFiles() = runBlocking<Unit> {
        val id = register()
        var calls = 0
        val outcome = importer().run(id, verifyHashes = true, isStopped = { calls++ > 3 })
        assertEquals(ImportOutcome.Cancelled, outcome)
        assertEquals(VersionState.IMPORTING, dao.version(id)!!.state)
        assertFalse(storage.versionDir(id).walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun missingDurationIsProbedAfterCopy() = runBlocking<Unit> {
        // Strip durations from the manifest, as a pack made without a probe would have.
        val manifestFile = File(packDir, "manifest.json")
        val m = PackManifestCodec.decode(manifestFile.readText())
        manifestFile.writeText(PackManifestCodec.encode(m.copy(files = m.files.map { it.copy(durationMs = null) })))
        val id = register()
        assertEquals(ImportOutcome.Success, importer().run(id, verifyHashes = true))
        assertTrue(dao.files(id).all { it.durationMs == 1234L })
    }

    @Test
    fun deleteRemovesRowsFilesAndResumption() = runBlocking<Unit> {
        val id = register()
        importer().run(id, verifyHashes = false)
        val resumption = ResumptionStore(PreferenceDataStoreFactory.create { File(work, "r.preferences_pb") })
        resumption.save(com.openbibleaudio.openbibleaudio.data.ResumptionState("bible/$id/GEN/2", 5000, 1))
        LibraryRepository(dao, storage, resumption).delete(id)
        assertNull(dao.version(id))
        assertTrue(dao.files(id).isEmpty())
        assertFalse(storage.versionDir(id).exists())
        assertNull(resumption.get())
    }
}

class ImportSpaceTest {
    @Test
    fun copyNeedsEverythingMoveNeedsTheLargestFile() {
        val mb = 1024L * 1024
        assertEquals(30 * mb + ImportSpace.MARGIN_BYTES, ImportSpace.requiredBytes(listOf(10 * mb, 20 * mb), move = false))
        assertEquals(20 * mb + ImportSpace.MARGIN_BYTES, ImportSpace.requiredBytes(listOf(10 * mb, 20 * mb), move = true))
        assertEquals(0, ImportSpace.requiredBytes(emptyList(), move = true))
    }
}
