package com.openbibleaudio.core.importer.fixtures

import com.openbibleaudio.core.importer.FileEntry
import com.openbibleaudio.core.importer.FileTree
import com.openbibleaudio.core.model.Book
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream

/** In-memory [FileTree] for tests. Applies the same ignore rules as the real trees. */
class InMemoryFileTree(files: Map<String, ByteArray>) : FileTree {
    private val files = files.toSortedMap()

    override fun walk(): Sequence<FileEntry> =
        files.asSequence().filter { !FileTree.isIgnored(it.key) }.map { FileEntry(it.key, it.value.size.toLong()) }

    override fun open(path: String): InputStream =
        ByteArrayInputStream(files[path] ?: throw FileNotFoundException(path))
}

/** BSB Gilbert file names (spec 0001 §3), generated from the canon. */
object BsbNames {
    private val abbreviations = mapOf(Book.TITUS to "Tts")

    fun abbreviation(book: Book): String =
        abbreviations[book] ?: (book.usfm.take(1) + book.usfm.drop(1).lowercase())

    fun fileName(book: Book, chapter: Int): String =
        "BSB_%02d_%s_%03d_G.mp3".format(book.ordinal + 1, abbreviation(book), chapter)

    /** All 1189 chapter file names. */
    fun all(): List<String> = Book.entries.flatMap { b -> (1..b.chapterCount).map { fileName(b, it) } }
}

/**
 * Builds synthetic MPEG Layer III streams: valid frame headers with zeroed payloads. Good enough
 * for frame-walking probes, not for decoders.
 */
object Mp3Fixtures {
    enum class Version(val bits: Int, val samplesPerFrame: Int) { MPEG1(3, 1152), MPEG2(2, 576), MPEG25(0, 576) }

    private val bitratesV1 = listOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val bitratesV2 = listOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val rates = mapOf(
        Version.MPEG1 to listOf(44100, 48000, 32000),
        Version.MPEG2 to listOf(22050, 24000, 16000),
        Version.MPEG25 to listOf(11025, 12000, 8000),
    )

    fun header(version: Version, bitrateKbps: Int, sampleRate: Int, padding: Boolean = false, mono: Boolean = true): ByteArray {
        val bitrateIndex = (if (version == Version.MPEG1) bitratesV1 else bitratesV2).indexOf(bitrateKbps)
        val rateIndex = rates.getValue(version).indexOf(sampleRate)
        require(bitrateIndex > 0 && rateIndex >= 0) { "unsupported $version $bitrateKbps kbps $sampleRate Hz" }
        val b1 = 0xE0 or (version.bits shl 3) or (1 shl 1) or 1 // Layer III, no CRC
        val b2 = (bitrateIndex shl 4) or (rateIndex shl 2) or ((if (padding) 1 else 0) shl 1)
        val b3 = (if (mono) 3 else 0) shl 6
        return byteArrayOf(0xFF.toByte(), b1.toByte(), b2.toByte(), b3.toByte())
    }

    fun frameLength(version: Version, bitrateKbps: Int, sampleRate: Int, padding: Boolean = false): Int =
        (if (version == Version.MPEG1) 144 else 72) * bitrateKbps * 1000 / sampleRate + (if (padding) 1 else 0)

    /** One frame with a zero payload. */
    fun frame(version: Version, bitrateKbps: Int, sampleRate: Int, padding: Boolean = false, mono: Boolean = true): ByteArray =
        header(version, bitrateKbps, sampleRate, padding, mono).copyOf(frameLength(version, bitrateKbps, sampleRate, padding))

    /** [count] frames; every [padEvery]-th frame padded (0 = never), like a real CBR encoder. */
    fun cbr(count: Int, version: Version = Version.MPEG1, bitrateKbps: Int = 192, sampleRate: Int = 44100, mono: Boolean = true, padEvery: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(count) { i ->
            val padded = padEvery > 0 && i % padEvery == 0
            out.write(frame(version, bitrateKbps, sampleRate, padded, mono))
        }
        return out.toByteArray()
    }

    /** A first frame carrying a Xing (or Info) tag with [frames] in its frame-count field. */
    fun xingFrame(frames: Int, tag: String = "Xing", version: Version = Version.MPEG1, bitrateKbps: Int = 128, sampleRate: Int = 44100, mono: Boolean = false): ByteArray {
        val f = frame(version, bitrateKbps, sampleRate, mono = mono)
        val offset = 4 + if (version == Version.MPEG1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        tag.toByteArray().copyInto(f, offset)
        int32(1).copyInto(f, offset + 4) // flags: frames field present
        int32(frames).copyInto(f, offset + 8)
        return f
    }

    /** A first frame carrying a VBRI tag with [frames] in its frame-count field. */
    fun vbriFrame(frames: Int, bitrateKbps: Int = 128, sampleRate: Int = 44100): ByteArray {
        val f = frame(Version.MPEG1, bitrateKbps, sampleRate, mono = false)
        "VBRI".toByteArray().copyInto(f, 36)
        int32(frames).copyInto(f, 36 + 14)
        return f
    }

    /** ID3v2 tag of version 2.[minor] whose body is [bodySize] bytes (a PRIV-like blob). */
    fun id3v2(bodySize: Int, minor: Int = 3, footer: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val flags = if (footer) 0x10 else 0
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), minor.toByte(), 0, flags.toByte()))
        out.write(synchsafe(bodySize))
        // Body: a fake PRIV frame full of bytes that look like MPEG sync, to make sure they're skipped.
        out.write(ByteArray(bodySize) { if (it % 2 == 0) 0xFF.toByte() else 0xFB.toByte() })
        if (footer) {
            out.write(byteArrayOf('3'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), minor.toByte(), 0, flags.toByte()))
            out.write(synchsafe(bodySize))
        }
        return out.toByteArray()
    }

    fun id3v1(): ByteArray = ByteArray(128).also { "TAG".toByteArray().copyInto(it) }

    /** Expected duration in ms for [samples] at [sampleRate], rounded like the probe. */
    fun durationMs(samples: Long, sampleRate: Int): Long = (samples * 1000 + sampleRate / 2) / sampleRate

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun synchsafe(v: Int) = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte(),
    )
}

fun bytes(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { acc, p -> acc + p }
