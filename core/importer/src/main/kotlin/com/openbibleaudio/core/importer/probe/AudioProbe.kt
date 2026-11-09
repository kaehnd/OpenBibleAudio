package com.openbibleaudio.core.importer.probe

import com.openbibleaudio.core.importer.FileEntry
import java.nio.file.Path

/**
 * Determines an audio file's duration (AD-5, spec 0001 §6.4). A probe is fed the same bytes the
 * hasher reads, so every file is read exactly once.
 */
interface AudioProbe {
    /** False if this probe can't handle [entry]; the caller then tries the next probe. */
    fun accepts(entry: FileEntry, localFile: Path?): Boolean

    fun newSession(entry: FileEntry, localFile: Path?): ProbeSession
}

interface ProbeSession {
    fun update(buf: ByteArray, off: Int, len: Int)

    /** Called once, after the last [update]. */
    fun finish(): ProbeResult
}

sealed interface ProbeResult {
    data class Ok(val durationMs: Long) : ProbeResult
    data class Failed(val reason: String) : ProbeResult
}

object AudioProbes {
    /** The default probe chain: MP3 frame scan first, then ffprobe if it's on PATH. */
    val default: List<AudioProbe> = listOf(Mp3FrameProbe, FfprobeProbe())
}
