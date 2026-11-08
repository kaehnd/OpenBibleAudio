package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.profile.BsbGilbertProfile
import com.openbibleaudio.core.model.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in test against the real BSB download (spec 0001 §8). Skipped unless the folder is given:
 *   ./gradlew :core:importer:test -Poba.corpus.bsb=<abs path>/bible_audio/bsb_gilbert
 * or the OBA_CORPUS_BSB environment variable is set.
 */
class BsbCorpusTest {
    @Test
    fun realCorpusProducesACompleteValidManifest() {
        val dir = (System.getProperty("oba.corpus.bsb") ?: System.getenv("OBA_CORPUS_BSB"))?.let { Path.of(it) }
        assumeTrue("BSB corpus not configured", dir != null && Files.isDirectory(dir))
        dir!!

        val result = runBlocking { ImporterPipeline.run(LocalFileTree(dir), BsbGilbertProfile.profile, ImportOptions(hash = false)) }
        assertEquals(emptyList(), result.report.issues)
        val manifest = assertNotNull(result.manifest)
        assertEquals(Book.entries.sumOf { it.chapterCount }, manifest.chapters.size)

        // Cross-check the frame walker against an independent estimate. These files are MPEG-1
        // 192 kbps 44.1 kHz with unpadded 626-byte frames (1152 samples each), so
        // duration = audio bytes / 626 × 1152 / 44.1 kHz, where audio bytes excludes the leading
        // ID3v2 tag (8–18 KB of XMP) and the trailing 128-byte ID3v1 tag.
        for (f in manifest.files) {
            val audioBytes = f.sizeBytes - id3v2Size(dir.resolve(f.path)) - 128
            val estimateMs = audioBytes / 626.0 * 1152 * 1000 / 44100
            val duration = assertNotNull(f.durationMs, f.path)
            assertTrue(abs(duration - estimateMs) <= 50, "${f.path}: $duration ms vs estimate $estimateMs ms")
        }
    }

    private fun id3v2Size(file: Path): Long {
        val h = Files.newInputStream(file).use { it.readNBytes(10) }
        if (h.size < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        return 10L + ((h[6].toInt() and 0x7F) shl 21 or ((h[7].toInt() and 0x7F) shl 14) or ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F))
    }
}
