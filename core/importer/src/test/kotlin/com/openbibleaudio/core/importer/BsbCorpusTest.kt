package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.profile.LayoutProfiles
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
 * Opt-in test against a real openbible.com BSB download (spec 0001 §8). Skipped unless the
 * folder is given:
 *   ./gradlew :core:importer:test -Poba.corpus.bsb=<abs path>/bible_audio/bsb_gilbert
 * or the OBA_CORPUS_BSB environment variable is set. The profile defaults to `bsb-gilbert`;
 * for the Souer recording add -Poba.corpus.profile=bsb-souer (or OBA_CORPUS_PROFILE).
 */
class BsbCorpusTest {
    @Test
    fun realCorpusProducesACompleteValidManifest() {
        val dir = (System.getProperty("oba.corpus.bsb") ?: System.getenv("OBA_CORPUS_BSB"))?.let { Path.of(it) }
        assumeTrue("BSB corpus not configured", dir != null && Files.isDirectory(dir))
        dir!!

        val profileId = System.getProperty("oba.corpus.profile") ?: System.getenv("OBA_CORPUS_PROFILE") ?: "bsb-gilbert"
        val profile = assertNotNull(LayoutProfiles.byId(profileId), "unknown profile '$profileId'")

        val result = runBlocking { ImporterPipeline.run(LocalFileTree(dir), profile, ImportOptions(hash = false)) }
        assertEquals(emptyList(), result.report.issues)
        val manifest = assertNotNull(result.manifest)
        assertEquals(Book.entries.sumOf { it.chapterCount }, manifest.chapters.size)

        // Cross-check the frame walker against an independent estimate. These files are MPEG-1
        // CBR 44.1 kHz mono (1152 samples per frame), so
        // duration = audio bytes / bytes per frame x 1152 / 44.1 kHz, where audio bytes excludes
        // the leading ID3v2 tag (8-18 KB of XMP for Gilbert, small for Souer) and, if present, the
        // trailing 128-byte ID3v1 tag (Gilbert has one, Souer doesn't). The bitrate comes from the
        // first frame header: Gilbert is 192 kbps throughout, Souer is 192 kbps (OT) and 128 kbps
        // (NT). Gilbert's frames are all unpadded (floor of 144 x bitrate / rate). Souer pads the
        // standard way, which averages exactly 144 x bitrate / rate. Souer's LAME Info frame
        // counts as one 26 ms frame here, well within the tolerance.
        val unpadded = profileId == "bsb-gilbert"
        for (f in manifest.files) {
            val file = dir.resolve(f.path)
            val id3 = id3v2Size(file)
            val bitrate = firstFrameBitrate(file, id3)
            val exact = 144.0 * bitrate / 44_100
            val bytesPerFrame = if (unpadded) kotlin.math.floor(exact) else exact
            val audioBytes = f.sizeBytes - id3 - (if (hasId3v1(file)) 128 else 0)
            val estimateMs = audioBytes / bytesPerFrame * 1152 * 1000 / 44100
            val duration = assertNotNull(f.durationMs, f.path)
            assertTrue(abs(duration - estimateMs) <= 50, "${f.path}: $duration ms vs estimate $estimateMs ms")
        }
    }

    /** Bitrate in bit/s of the MPEG-1 Layer III frame right after the ID3v2 tag. */
    private fun firstFrameBitrate(file: Path, offset: Long): Int = Files.newByteChannel(file).use { ch ->
        val h = java.nio.ByteBuffer.allocate(4)
        ch.position(offset).read(h)
        assertEquals(0xFF, h[0].toInt() and 0xFF, "$file: no frame sync after ID3v2")
        val kbps = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        kbps[(h[2].toInt() and 0xFF) shr 4] * 1000
    }

    private fun hasId3v1(file: Path): Boolean = Files.newByteChannel(file).use { ch ->
        val tag = java.nio.ByteBuffer.allocate(3)
        ch.position(ch.size() - 128).read(tag)
        tag.array().contentEquals("TAG".toByteArray())
    }

    private fun id3v2Size(file: Path): Long {
        val h = Files.newInputStream(file).use { it.readNBytes(10) }
        if (h.size < 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return 0
        return 10L + ((h[6].toInt() and 0x7F) shl 21 or ((h[7].toInt() and 0x7F) shl 14) or ((h[8].toInt() and 0x7F) shl 7) or (h[9].toInt() and 0x7F))
    }
}
