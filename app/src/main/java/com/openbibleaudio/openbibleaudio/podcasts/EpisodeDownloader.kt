package com.openbibleaudio.openbibleaudio.podcasts

import android.util.Log
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.library.DurationProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface DownloadOutcome {
    data object Done : DownloadOutcome

    /** The publisher no longer serves it (404/410, …). The episode is FAILED and isn't tried again automatically. */
    data class Gone(val message: String) : DownloadOutcome

    /** A network or server problem. The `.part` file is kept for resuming. */
    data class Retry(val message: String) : DownloadOutcome

    /** Not enough storage. Nothing is marked FAILED, so the next trigger tries again. */
    data class NoSpace(val message: String) : DownloadOutcome
}

/**
 * Downloads one episode (spec 0003 §8.2): resumable via `Range` + `If-Range`, streamed to a
 * `.part` file, size-checked, fsynced and renamed into place, then recorded as DOWNLOADED. The
 * worker adds scheduling, the notification and progress on top.
 */
class EpisodeDownloader(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val dao: PodcastDao,
    private val storage: PodcastStorage,
    private val prober: DurationProber,
) {
    /**
     * Downloads [episode]. [onProgress] gets (bytes so far, expected total or null) after each chunk.
     * Coroutine cancellation stops the transfer, and the `.part` is kept.
     */
    suspend fun download(episode: PodcastEpisodeEntity, onProgress: (Long, Long?) -> Unit = { _, _ -> }): DownloadOutcome =
        withContext(Dispatchers.IO) {
            val dest = storage.file(episode.feedId, episode.guidHash)
            val part = storage.partFile(dest)
            val validatorFile = storage.validatorFile(dest)
            val url = episode.enclosureUrl.toHttpUrlOrNull()
                ?: return@withContext gone(episode, "The episode address isn't a web address")
            dest.parentFile?.mkdirs()
            dao.setDownload(episode.feedId, episode.guidHash, DownloadState.DOWNLOADING, null, part.length().takeIf { it > 0 }, null)

            // Resume only against a known validator, so a changed file is never spliced.
            val validator = validatorFile.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
            val resumeFrom = if (part.isFile && validator != null) part.length() else 0L
            if (resumeFrom == 0L) part.delete()

            val request = Request.Builder().url(url).header("User-Agent", userAgent).apply {
                if (resumeFrom > 0) {
                    header("Range", "bytes=$resumeFrom-")
                    header("If-Range", validator!!)
                }
            }.build()

            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 206 && resumeFrom > 0 && rangeStart(response) == resumeFrom ->
                            receive(episode, response, part, validatorFile, append = true, offset = resumeFrom, total = rangeTotal(response), onProgress)
                        response.code == 200 ->
                            receive(episode, response, part, validatorFile, append = false, offset = 0, total = response.body?.contentLength()?.takeIf { it >= 0 }, onProgress)
                        response.code == 416 || response.code == 206 -> {
                            // The partial file doesn't line up with the server's copy: start over.
                            part.delete()
                            validatorFile.delete()
                            retry(episode, "The download had to restart")
                        }
                        response.code == 404 || response.code == 410 -> gone(episode, NO_LONGER_AVAILABLE)
                        response.code >= 500 || response.code == 408 || response.code == 429 ->
                            retry(episode, "The server answered HTTP ${response.code}")
                        else -> gone(episode, "The server refused the download (HTTP ${response.code})")
                    }
                }?.let { return@withContext it }
                finish(episode, dest, part, validatorFile)
            } catch (e: IOException) {
                if (isNoSpace(e)) noSpace(episode) else retry(episode, e.message ?: "Network error")
            } catch (e: CancellationException) {
                // Stopped by the user or the system: back in the queue, .part kept for resuming.
                withContext(NonCancellable) {
                    dao.setDownload(episode.feedId, episode.guidHash, DownloadState.QUEUED, null, part.length().takeIf { it > 0 }, null)
                }
                throw e
            }
        }

    /** Streams the body into [part]. Returns null when every expected byte arrived, else the outcome. */
    private suspend fun receive(
        episode: PodcastEpisodeEntity,
        response: Response,
        part: File,
        validatorFile: File,
        append: Boolean,
        offset: Long,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): DownloadOutcome? {
        val body = response.body ?: return retry(episode, "The server sent no data")
        val expected = total ?: body.contentLength().takeIf { it >= 0 }?.let { it + offset }
        if (expected != null && expected - offset > storage.allocatableBytes() - SPACE_MARGIN) return noSpace(episode)

        // Remember what this .part was downloaded against, for If-Range on a later resume.
        val newValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified")
        if (!append) {
            if (newValidator != null) validatorFile.writeText(newValidator) else validatorFile.delete()
        }

        var written = offset
        FileOutputStream(part, append).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(written, expected)
                }
            }
            out.flush()
            out.fd.sync()
        }
        if (expected != null && written != expected) return retry(episode, "The download stopped early ($written of $expected bytes)")
        val listed = episode.enclosureLength
        if (listed != null && listed > 0 && listed != written) {
            Log.w(TAG, "${episode.guidHash}: got $written bytes, the feed lists $listed")
        }
        return null
    }

    private suspend fun finish(episode: PodcastEpisodeEntity, dest: File, part: File, validatorFile: File): DownloadOutcome {
        dest.delete()
        if (!part.renameTo(dest)) return retry(episode, "Couldn't move the finished download into place")
        validatorFile.delete()
        val duration = episode.durationMs ?: prober.durationMs(dest)
        dao.setDownloaded(episode.feedId, episode.guidHash, dest.path, dest.length(), duration)
        return DownloadOutcome.Done
    }

    private suspend fun gone(e: PodcastEpisodeEntity, message: String): DownloadOutcome {
        storage.delete(e.feedId, e.guidHash)
        dao.setDownload(e.feedId, e.guidHash, DownloadState.FAILED, null, null, message)
        return DownloadOutcome.Gone(message)
    }

    private suspend fun retry(e: PodcastEpisodeEntity, message: String): DownloadOutcome {
        val part = storage.partFile(storage.file(e.feedId, e.guidHash))
        dao.setDownload(e.feedId, e.guidHash, DownloadState.QUEUED, null, part.length().takeIf { it > 0 }, message)
        return DownloadOutcome.Retry(message)
    }

    private suspend fun noSpace(e: PodcastEpisodeEntity): DownloadOutcome {
        dao.setDownload(e.feedId, e.guidHash, DownloadState.NONE, null, null, NO_SPACE)
        return DownloadOutcome.NoSpace(NO_SPACE)
    }

    private fun rangeStart(r: Response): Long? = CONTENT_RANGE.find(r.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    private fun rangeTotal(r: Response): Long? = CONTENT_RANGE.find(r.header("Content-Range").orEmpty())?.groupValues?.get(3)?.toLongOrNull()

    private fun isNoSpace(e: IOException): Boolean = e.message?.contains("ENOSPC") == true || e.message?.contains("No space left") == true

    companion object {
        const val NO_LONGER_AVAILABLE = "No longer available from the publisher"
        const val NO_SPACE = "Not enough storage space"
        private const val TAG = "EpisodeDownloader"
        private const val BUFFER = 64 * 1024

        /** Kept free so a download never fills the device (same margin as the ALL-mode check, §8.3). */
        const val SPACE_MARGIN = 256L * 1024 * 1024
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}
