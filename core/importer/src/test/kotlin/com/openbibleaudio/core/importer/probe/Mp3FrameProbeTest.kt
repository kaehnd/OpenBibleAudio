package com.openbibleaudio.core.importer.probe

import com.openbibleaudio.core.importer.FileEntry
import com.openbibleaudio.core.importer.fixtures.Mp3Fixtures
import com.openbibleaudio.core.importer.fixtures.Mp3Fixtures.Version
import com.openbibleaudio.core.importer.fixtures.bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Mp3FrameProbeTest {

    private fun probe(data: ByteArray, chunk: Int = 64 * 1024): ProbeResult {
        val session = Mp3FrameProbe.newSession(FileEntry("x.mp3", data.size.toLong()), null)
        var i = 0
        while (i < data.size) {
            val n = minOf(chunk, data.size - i)
            session.update(data, i, n)
            i += n
        }
        return session.finish()
    }

    private fun assertDuration(expectedMs: Long, data: ByteArray) {
        val result = probe(data)
        assertIs<ProbeResult.Ok>(result, "probe failed: $result")
        assertEquals(expectedMs, result.durationMs)
    }

    @Test
    fun cbrWithoutVbrHeaderBsbShape() {
        // MPEG-1 192 kbps 44.1 kHz mono, padding like a real encoder, no Xing header.
        val frames = 9375
        assertDuration(Mp3Fixtures.durationMs(frames * 1152L, 44100), Mp3Fixtures.cbr(frames, padEvery = 3))
    }

    @Test
    fun leadingId3v23TagWithLargePrivFrameIsSkipped() {
        val data = bytes(Mp3Fixtures.id3v2(8076), Mp3Fixtures.cbr(500))
        assertDuration(Mp3Fixtures.durationMs(500 * 1152L, 44100), data)
    }

    @Test
    fun id3v24TagWithFooterAndTrailingId3v1() {
        val data = bytes(Mp3Fixtures.id3v2(300, minor = 4, footer = true), Mp3Fixtures.cbr(200), Mp3Fixtures.id3v1())
        assertDuration(Mp3Fixtures.durationMs(200 * 1152L, 44100), data)
    }

    @Test
    fun consecutiveId3v2Tags() {
        val data = bytes(Mp3Fixtures.id3v2(100), Mp3Fixtures.id3v2(50), Mp3Fixtures.cbr(10))
        assertDuration(Mp3Fixtures.durationMs(10 * 1152L, 44100), data)
    }

    @Test
    fun xingFrameCountIsUsed() {
        // The Xing count says 1000 frames; only 5 follow, which proves the count wins.
        val data = bytes(Mp3Fixtures.xingFrame(1000), Mp3Fixtures.cbr(5, bitrateKbps = 128, mono = false))
        assertDuration(Mp3Fixtures.durationMs(1000 * 1152L, 44100), data)
    }

    @Test
    fun infoTagInMonoMpeg1() {
        val data = bytes(Mp3Fixtures.xingFrame(42, tag = "Info", mono = true), Mp3Fixtures.cbr(3, bitrateKbps = 128))
        assertDuration(Mp3Fixtures.durationMs(42 * 1152L, 44100), data)
    }

    @Test
    fun xingInMpeg2UsesMpeg2Offsets() {
        val data = bytes(
            Mp3Fixtures.xingFrame(300, version = Version.MPEG2, bitrateKbps = 64, sampleRate = 22050),
            Mp3Fixtures.cbr(3, Version.MPEG2, 64, 22050, mono = false),
        )
        assertDuration(Mp3Fixtures.durationMs(300 * 576L, 22050), data)
    }

    @Test
    fun vbriFrameCountIsUsed() {
        val data = bytes(Mp3Fixtures.vbriFrame(777), Mp3Fixtures.cbr(4, bitrateKbps = 128, mono = false))
        assertDuration(Mp3Fixtures.durationMs(777 * 1152L, 44100), data)
    }

    @Test
    fun mpeg2And25Use576SamplesPerFrame() {
        assertDuration(Mp3Fixtures.durationMs(400 * 576L, 22050), Mp3Fixtures.cbr(400, Version.MPEG2, 64, 22050))
        assertDuration(Mp3Fixtures.durationMs(400 * 576L, 8000), Mp3Fixtures.cbr(400, Version.MPEG25, 32, 8000))
    }

    @Test
    fun falseSyncInJunkBeforeAudioIsNotCounted() {
        // Junk containing a lone plausible header that isn't followed by another one.
        val junk = ByteArray(700).also { Mp3Fixtures.header(Version.MPEG1, 128, 48000).copyInto(it, 10) }
        val data = bytes(junk, Mp3Fixtures.cbr(100))
        assertDuration(Mp3Fixtures.durationMs(100 * 1152L, 44100), data)
    }

    @Test
    fun truncatedLastFrameIsDropped() {
        val full = Mp3Fixtures.cbr(50)
        val data = full.copyOf(full.size - 100)
        assertDuration(Mp3Fixtures.durationMs(49 * 1152L, 44100), data)
    }

    @Test
    fun junkAfterAudioEndsTheWalk() {
        val data = bytes(Mp3Fixtures.cbr(20), ByteArray(1000) { 0x55 }, "APETAGEX".toByteArray())
        assertDuration(Mp3Fixtures.durationMs(20 * 1152L, 44100), data)
    }

    @Test
    fun noFramesFails() {
        assertIs<ProbeResult.Failed>(probe(ByteArray(5000) { 0x11 }))
        assertIs<ProbeResult.Failed>(probe(Mp3Fixtures.id3v2(100)))
        assertIs<ProbeResult.Failed>(probe(ByteArray(0)))
    }

    @Test
    fun byteAtATimeEqualsLargeChunks() {
        val data = bytes(Mp3Fixtures.id3v2(1234, minor = 4, footer = true), Mp3Fixtures.cbr(300, padEvery = 2), Mp3Fixtures.id3v1())
        val expected = probe(data)
        assertIs<ProbeResult.Ok>(expected)
        assertEquals(expected, probe(data, chunk = 1))
        assertEquals(expected, probe(data, chunk = 7))
        assertEquals(expected, probe(data, chunk = 627))
    }

    @Test
    fun acceptsOnlyMp3Extension() {
        assertTrue(Mp3FrameProbe.accepts(FileEntry("a/B.MP3", 1), null))
        assertFalse(Mp3FrameProbe.accepts(FileEntry("a/b.m4a", 1), null))
    }

    @Test
    fun headerParsingRejectsInvalidValues() {
        val good = Mp3Fixtures.header(Version.MPEG1, 192, 44100)
        assertEquals(626, Mp3Header.parse(good, 0)!!.frameLength)
        fun variant(i: Int, transform: (Int) -> Int) = good.copyOf().also { it[i] = transform(it[i].toInt() and 0xFF).toByte() }
        assertEquals(null, Mp3Header.parse(variant(1) { (it and 0xF9) or (3 shl 1) }, 0)) // Layer I
        assertEquals(null, Mp3Header.parse(variant(1) { (it and 0xE7) or (1 shl 3) }, 0)) // reserved version
        assertEquals(null, Mp3Header.parse(variant(2) { it and 0x0F }, 0)) // free format
        assertEquals(null, Mp3Header.parse(variant(2) { it or 0xF0 }, 0)) // bad bitrate
        assertEquals(null, Mp3Header.parse(variant(2) { it or 0x0C }, 0)) // reserved sample rate
    }
}
