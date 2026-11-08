package com.openbibleaudio.core.importer.probe

import com.openbibleaudio.core.importer.FileEntry
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Optional fallback for formats other than MP3 (spec 0001 §6.4). It only works for files on the
 * local file system, and only if `ffprobe` is on PATH. Bytes fed to the session are ignored.
 */
class FfprobeProbe(private val executable: String = "ffprobe") : AudioProbe {
    private val available: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder(executable, "-version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    override fun accepts(entry: FileEntry, localFile: Path?): Boolean =
        localFile != null && !entry.path.endsWith(".mp3", ignoreCase = true) && available

    override fun newSession(entry: FileEntry, localFile: Path?): ProbeSession = object : ProbeSession {
        override fun update(buf: ByteArray, off: Int, len: Int) = Unit

        override fun finish(): ProbeResult = runCatching {
            val p = ProcessBuilder(
                executable, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0",
                requireNotNull(localFile).toString(),
            ).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.errorStream.readBytes()
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) return@runCatching ProbeResult.Failed("ffprobe failed")
            val seconds = out.toDoubleOrNull() ?: return@runCatching ProbeResult.Failed("ffprobe printed '$out'")
            ProbeResult.Ok((seconds * 1000).roundToLong())
        }.getOrElse { ProbeResult.Failed("ffprobe: ${it.message}") }
    }
}
