package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.probe.AudioProbe
import com.openbibleaudio.core.importer.probe.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** What one streaming pass over a file found (spec 0001 §6.5). */
data class FileFacts(
    val path: String,
    val bytesRead: Long,
    val sha256: String?,
    /** Null if no probe accepted the file. */
    val probe: ProbeResult?,
    /** Set if the file couldn't be read; the other fields are then meaningless. */
    val readError: String? = null,
)

fun interface ProgressListener {
    fun onProgress(doneFiles: Int, totalFiles: Int, doneBytes: Long, totalBytes: Long)
}

/** Reads each file once, feeding SHA-256 and the first accepting probe from the same buffer. */
class FileScanner(
    private val probes: List<AudioProbe>,
    private val hash: Boolean,
    private val jobs: Int = defaultJobs(),
    private val progress: ProgressListener? = null,
) {
    init {
        require(jobs >= 1) { "jobs must be >= 1" }
    }

    /** Returns facts in the same order as [entries], whatever the parallelism. */
    suspend fun scan(tree: FileTree, entries: List<FileEntry>): List<FileFacts> = coroutineScope {
        val totalBytes = entries.sumOf { it.sizeBytes }
        val doneFiles = AtomicInteger()
        val doneBytes = AtomicLong()
        val dispatcher = Dispatchers.IO.limitedParallelism(jobs)
        entries.map { entry ->
            async(dispatcher) {
                read(tree, entry).also {
                    val bytes = doneBytes.addAndGet(entry.sizeBytes)
                    progress?.onProgress(doneFiles.incrementAndGet(), entries.size, bytes, totalBytes)
                }
            }
        }.awaitAll()
    }

    private suspend fun read(tree: FileTree, entry: FileEntry): FileFacts = withContext(Dispatchers.IO) {
        val local = tree.localPath(entry.path)
        val session = probes.firstOrNull { it.accepts(entry, local) }?.newSession(entry, local)
        val digest = if (hash) MessageDigest.getInstance("SHA-256") else null
        try {
            var total = 0L
            tree.open(entry.path).use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    digest?.update(buf, 0, n)
                    session?.update(buf, 0, n)
                    total += n
                }
            }
            FileFacts(entry.path, total, digest?.digest()?.toHex(), session?.finish())
        } catch (e: java.io.IOException) {
            FileFacts(entry.path, 0, null, null, readError = e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val BUFFER_SIZE = 64 * 1024

        fun defaultJobs(): Int = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

        private fun ByteArray.toHex(): String = java.util.HexFormat.of().formatHex(this)
    }
}
