package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.fixtures.BsbNames
import com.openbibleaudio.core.importer.fixtures.InMemoryFileTree
import com.openbibleaudio.core.importer.fixtures.Mp3Fixtures
import com.openbibleaudio.core.importer.fixtures.bytes
import com.openbibleaudio.core.importer.probe.AudioProbes
import com.openbibleaudio.core.importer.probe.Mp3FrameProbe
import com.openbibleaudio.core.importer.profile.BsbGilbertProfile
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.pack.PackGenerator
import com.openbibleaudio.core.model.pack.PackManifestCodec
import com.openbibleaudio.core.model.pack.Severity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImporterPipelineTest {
    private val profile = BsbGilbertProfile.profile
    private val generator = PackGenerator("openbibleaudio-importer-cli", "0.1.0", "bsb-gilbert")

    /** A tiny MP3 whose frame count encodes the chapter, so files differ. */
    private fun audio(frames: Int) = bytes(Mp3Fixtures.id3v2(64), Mp3Fixtures.cbr(frames))

    private fun miniTree() = InMemoryFileTree(
        mapOf(
            BsbNames.fileName(Book.JUDE, 1) to audio(30),
            BsbNames.fileName(Book.GENESIS, 2) to audio(20),
            BsbNames.fileName(Book.GENESIS, 1) to audio(10),
            BsbNames.fileName(Book.TITUS, 3) to audio(40),
            "notes.txt" to "hello".toByteArray(),
            ".DS_Store" to ByteArray(3),
            "manifest.json" to "{}".toByteArray(),
        ),
    )

    private fun run(tree: FileTree, options: ImportOptions = ImportOptions(generator = generator, probes = listOf(Mp3FrameProbe))) =
        runBlocking { ImporterPipeline.run(tree, profile, options) }

    @Test
    fun goldenMiniManifest() {
        val result = run(miniTree())
        val manifest = assertNotNull(result.manifest, "${result.report.issues}")
        val actual = PackManifestCodec.encode(manifest)
        if (System.getenv("OBA_UPDATE_GOLDEN") == "1") {
            // Regenerate after an intentional format change: OBA_UPDATE_GOLDEN=1 ./gradlew :core:importer:test
            java.io.File("src/test/resources/golden/mini-manifest.json").apply { parentFile.mkdirs() }.writeText(actual)
        }
        val expected = javaClass.getResource("/golden/mini-manifest.json")?.readText()?.replace("\r\n", "\n")
        assertEquals(expected, actual)
    }

    @Test
    fun reportCountsAndIssues() {
        val report = run(miniTree()).report
        assertEquals(5, report.scannedFiles) // .DS_Store and manifest.json are ignored
        assertEquals(4, report.matchedFiles)
        assertEquals(1, report.unmatchedFiles)
        assertEquals(setOf(IssueCode.UNMATCHED_FILE, IssueCode.MISSING_CHAPTERS), report.issues.map { it.code }.toSet())
    }

    @Test
    fun outputIsDeterministicAcrossParallelism() {
        val tree = InMemoryFileTree(BsbNames.all().take(120).mapIndexed { i, n -> n to audio(i + 1) }.toMap())
        val one = run(tree, ImportOptions(generator = generator, jobs = 1, probes = AudioProbes.default)).manifest!!
        val eight = run(tree, ImportOptions(generator = generator, jobs = 8, probes = AudioProbes.default)).manifest!!
        assertEquals(PackManifestCodec.encode(one), PackManifestCodec.encode(eight))
        assertEquals(120, one.chapters.size)
    }

    @Test
    fun fullCanonIsCompleteWithNoIssues() {
        val tree = InMemoryFileTree(BsbNames.all().associateWith { audio(3) })
        val result = run(tree)
        assertEquals(emptyList(), result.report.issues)
        assertEquals(1189, result.manifest!!.chapters.size)
        assertEquals(Book.GENESIS, result.manifest!!.chapters.first().book)
        assertEquals(Book.REVELATION, result.manifest!!.chapters.last().book)
    }

    @Test
    fun noHashOmitsSha256() {
        val m = run(miniTree(), ImportOptions(hash = false, probes = listOf(Mp3FrameProbe))).manifest!!
        assertTrue(m.files.all { it.sha256 == null && it.durationMs != null })
        assertNull(m.generator)
    }

    @Test
    fun duplicateChapterStopsBeforeReading() {
        // Same chapter under two names that both match after alias resolution (Tts and Tit).
        val tree = InMemoryFileTree(
            mapOf("BSB_56_Tts_001_G.mp3" to audio(1), "BSB_56_Tit_001_G.mp3" to audio(1)),
        )
        val result = run(tree)
        assertNull(result.manifest)
        assertEquals(IssueCode.DUPLICATE_CHAPTER, result.report.issues.single { it.severity == Severity.ERROR }.code)
    }

    @Test
    fun overridesWinOverProfileDefaults() {
        val options = ImportOptions(
            overrides = BibleOverrides(versionId = "bsb-test", narrator = "Someone", attribution = "Public domain"),
            probes = listOf(Mp3FrameProbe),
        )
        val bible = run(miniTree(), options).manifest!!.bible
        assertEquals("bsb-test", bible.versionId)
        assertEquals("BSB", bible.abbreviation)
        assertEquals("Someone", bible.narrator)
        assertEquals("Public domain", bible.attribution)
    }

    @Test
    fun missingRequiredBibleFieldIsAnError() {
        val noDefaults = profile.copy(bibleDefaults = null)
        val result = runBlocking { ImporterPipeline.run(miniTree(), noDefaults, ImportOptions(overrides = BibleOverrides(versionId = "x"))) }
        assertNull(result.manifest)
        assertEquals(3, result.report.issues.count { it.code == IssueCode.MISSING_BIBLE_FIELD })
    }

    @Test
    fun undecodableAudioIsAWarningWithoutDuration() {
        val tree = InMemoryFileTree(mapOf(BsbNames.fileName(Book.JUDE, 1) to ByteArray(500) { 7 }))
        val result = run(tree)
        val codes = result.report.issues.map { it.code }
        assertTrue(IssueCode.PROBE_FAILED in codes && IssueCode.NO_DURATION in codes, "$codes")
        assertNull(result.manifest!!.files.single().durationMs)
    }

    @Test
    fun emptyTreeIsAnError() {
        val result = run(InMemoryFileTree(mapOf("readme.txt" to ByteArray(1))))
        assertNull(result.manifest)
        assertTrue(result.report.issues.any { it.code == IssueCode.EMPTY_PACK })
    }
}
