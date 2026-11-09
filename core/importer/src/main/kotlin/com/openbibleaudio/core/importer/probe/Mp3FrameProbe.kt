package com.openbibleaudio.core.importer.probe

import com.openbibleaudio.core.importer.FileEntry
import java.nio.file.Path

/**
 * Duration of an MPEG Layer III file, computed from its frames (spec 0001 §6.4). It's an
 * incremental parser with no seeking, so it can share one streaming pass with the hasher.
 *
 * Uses the Xing/Info or VBRI frame count when the first frame has one. Otherwise it walks every
 * frame and sums samples, which is exact for CBR files without a VBR header (the BSB case).
 * Encoder delay/padding is not subtracted (open question 4).
 */
object Mp3FrameProbe : AudioProbe {
    override fun accepts(entry: FileEntry, localFile: Path?) = entry.path.endsWith(".mp3", ignoreCase = true)

    override fun newSession(entry: FileEntry, localFile: Path?): ProbeSession = Mp3FrameParser()
}

/** A decoded MPEG audio frame header (Layer III only). */
internal data class Mp3Header(
    val version: Int, // 1 = MPEG-1, 2 = MPEG-2, 25 = MPEG-2.5
    val sampleRate: Int,
    val bitrateKbps: Int,
    val padding: Boolean,
    val mono: Boolean,
) {
    val samplesPerFrame: Int get() = if (version == 1) 1152 else 576
    val frameLength: Int get() = (if (version == 1) 144 else 72) * bitrateKbps * 1000 / sampleRate + (if (padding) 1 else 0)

    /** Offset of a Xing/Info tag from the frame start (after the 4-byte header and side info). */
    val xingOffset: Int get() = 4 + if (version == 1) (if (mono) 17 else 32) else (if (mono) 9 else 17)

    fun compatibleWith(other: Mp3Header) = version == other.version && sampleRate == other.sampleRate

    companion object {
        private val BITRATE_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BITRATE_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val RATES = mapOf(1 to intArrayOf(44100, 48000, 32000), 2 to intArrayOf(22050, 24000, 16000), 25 to intArrayOf(11025, 12000, 8000))

        /** Parses 4 bytes at [i], or returns null if they aren't a valid Layer III header. */
        fun parse(b: ByteArray, i: Int): Mp3Header? {
            val b0 = b[i].toInt() and 0xFF
            val b1 = b[i + 1].toInt() and 0xFF
            val b2 = b[i + 2].toInt() and 0xFF
            val b3 = b[i + 3].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
            val version = when ((b1 shr 3) and 3) {
                0 -> 25
                2 -> 2
                3 -> 1
                else -> return null
            }
            if (((b1 shr 1) and 3) != 1) return null // Layer III only
            val bitrateIndex = (b2 shr 4) and 0xF
            if (bitrateIndex == 0 || bitrateIndex == 15) return null // free-format / bad
            val rateIndex = (b2 shr 2) and 3
            if (rateIndex == 3) return null
            val bitrate = (if (version == 1) BITRATE_V1 else BITRATE_V2)[bitrateIndex]
            return Mp3Header(
                version = version,
                sampleRate = RATES.getValue(version)[rateIndex],
                bitrateKbps = bitrate,
                padding = ((b2 shr 1) and 1) == 1,
                mono = ((b3 shr 6) and 3) == 3,
            )
        }
    }
}

internal class Mp3FrameParser : ProbeSession {
    private enum class State { TAG_CHECK, SKIP, SEARCH, LOCKED, DONE }

    private var buf = ByteArray(1 shl 17)
    private var start = 0
    private var end = 0
    private var state = State.TAG_CHECK
    private var skipRemaining = 0L

    private var reference: Mp3Header? = null
    private var frames = 0L
    private var samples = 0L
    private var vbrHeaderChecked = false
    private var vbrSamples: Long? = null

    override fun update(buf: ByteArray, off: Int, len: Int) {
        if (state == State.DONE) return
        append(buf, off, len)
        process(final = false)
    }

    override fun finish(): ProbeResult {
        if (state != State.DONE) process(final = true)
        val ref = reference ?: return ProbeResult.Failed("no MPEG Layer III frames found")
        val total = vbrSamples ?: samples
        if (total == 0L) return ProbeResult.Failed("no complete MPEG Layer III frames found")
        return ProbeResult.Ok((total * 1000 + ref.sampleRate / 2) / ref.sampleRate)
    }

    private val available get() = end - start

    private fun process(final: Boolean) {
        while (true) {
            when (state) {
                State.TAG_CHECK -> {
                    if (available < 10) {
                        if (!final) return
                        state = State.SEARCH
                        continue
                    }
                    if (buf[start] == 'I'.code.toByte() && buf[start + 1] == 'D'.code.toByte() && buf[start + 2] == '3'.code.toByte()) {
                        val size = synchsafe(start + 6)
                        val footer = if ((buf[start + 5].toInt() and 0x10) != 0) 10 else 0
                        skipRemaining = 10L + size + footer
                        state = State.SKIP
                    } else {
                        state = State.SEARCH
                    }
                }

                State.SKIP -> {
                    val n = minOf(available.toLong(), skipRemaining).toInt()
                    start += n
                    skipRemaining -= n
                    if (skipRemaining > 0) {
                        if (final) state = State.DONE
                        return
                    }
                    state = State.TAG_CHECK // consecutive tags
                }

                State.SEARCH -> if (!search(final)) return

                State.LOCKED -> if (!readFrame(final)) return

                State.DONE -> return
            }
        }
    }

    /** Looks for a header confirmed by a second header right after it. False = need more data. */
    private fun search(final: Boolean): Boolean {
        var i = start
        while (i + 4 <= end) {
            val h = Mp3Header.parse(buf, i)
            if (h != null && (reference == null || h.compatibleWith(reference!!))) {
                val next = i + h.frameLength
                if (next + 4 > end) {
                    if (final) {
                        // Can't confirm; a lone trailing frame isn't worth trusting. Stop here.
                        state = State.DONE
                        return false
                    }
                    start = i
                    return false
                }
                val n = Mp3Header.parse(buf, next)
                if (n != null && n.compatibleWith(h)) {
                    if (reference == null) reference = h
                    start = i
                    state = State.LOCKED
                    return true
                }
            }
            i++
        }
        if (final) {
            state = State.DONE
            return false
        }
        start = maxOf(start, end - 3) // keep a possible partial header
        return false
    }

    /** Consumes one frame at [start]. False = need more data (or done). */
    private fun readFrame(final: Boolean): Boolean {
        if (available < 4) {
            if (final) state = State.DONE
            return false
        }
        val h = Mp3Header.parse(buf, start)
        if (h == null || !h.compatibleWith(reference!!)) {
            state = if (startsWithTrailingTag()) State.DONE else State.SEARCH
            if (state == State.SEARCH) start++ // don't re-find the same position
            return state != State.DONE
        }
        val len = h.frameLength
        if (available < len) {
            if (final) state = State.DONE // truncated final frame: dropped
            return false
        }
        if (!vbrHeaderChecked) {
            vbrHeaderChecked = true
            val vbrFrames = vbrFrameCount(h, len)
            if (vbrFrames != null) {
                vbrSamples = vbrFrames * h.samplesPerFrame
                state = State.DONE
                return false
            }
        }
        frames++
        samples += h.samplesPerFrame
        start += len
        return true
    }

    /** Frame count from a Xing/Info or VBRI tag in the first frame, if present. */
    private fun vbrFrameCount(h: Mp3Header, len: Int): Long? {
        val x = start + h.xingOffset
        if (x + 12 <= start + len && (matches(x, "Xing") || matches(x, "Info"))) {
            val flags = int32(x + 4)
            if ((flags and 1) != 0) return int32(x + 8).toLong() and 0xFFFFFFFFL
        }
        val v = start + 4 + 32
        if (v + 18 <= start + len && matches(v, "VBRI")) {
            return int32(v + 14).toLong() and 0xFFFFFFFFL
        }
        return null
    }

    private fun startsWithTrailingTag(): Boolean =
        (available >= 3 && matches(start, "TAG")) ||
            (available >= 8 && matches(start, "APETAGEX")) ||
            (available >= 11 && matches(start, "LYRICSBEGIN"))

    private fun matches(at: Int, s: String) = s.indices.all { at + it < end && buf[at + it] == s[it].code.toByte() }

    private fun int32(at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)

    private fun synchsafe(at: Int): Int =
        ((buf[at].toInt() and 0x7F) shl 21) or ((buf[at + 1].toInt() and 0x7F) shl 14) or
            ((buf[at + 2].toInt() and 0x7F) shl 7) or (buf[at + 3].toInt() and 0x7F)

    private fun append(src: ByteArray, off: Int, len: Int) {
        if (end + len > buf.size) {
            val live = available
            if (live + len > buf.size) {
                buf = buf.copyOfRange(start, end).copyOf(maxOf(buf.size * 2, live + len))
            } else {
                System.arraycopy(buf, start, buf, 0, live)
            }
            start = 0
            end = live
        }
        System.arraycopy(src, off, buf, end, len)
        end += len
    }
}
