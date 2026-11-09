package com.openbibleaudio.openbibleaudio.library

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import android.util.Log
import com.openbibleaudio.core.model.pack.ManifestFormatException
import com.openbibleaudio.core.model.pack.PackManifestCodec
import com.openbibleaudio.openbibleaudio.data.BibleStorage
import com.openbibleaudio.openbibleaudio.data.db.AudioFileEntity
import com.openbibleaudio.openbibleaudio.data.db.ChapterAudioEntity
import com.openbibleaudio.openbibleaudio.data.db.LibraryDao
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/** Duration of a local audio file, for files whose manifest entry has none (spec 0002 §6.2). */
fun interface DurationProber {
    fun durationMs(file: File): Long?

    companion object {
        val MEDIA_METADATA = DurationProber { file ->
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (e: RuntimeException) {
                null
            } finally {
                r.release()
            }
        }
    }
}

data class ImportProgress(val doneFiles: Int, val totalFiles: Int, val doneBytes: Long, val totalBytes: Long)

sealed interface ImportOutcome {
    data object Success : ImportOutcome
    /** Transient IO trouble: retry later; verified files are kept. */
    data class Retry(val reason: String) : ImportOutcome
    data class Failure(val reason: String) : ImportOutcome
    data object Cancelled : ImportOutcome
}

/**
 * Copies a pack into app storage (AD-6, spec 0002 §6.2). Resumable: files already verified
 * are skipped. This holds the worker's logic, so it can be tested without WorkManager.
 */
class PackImporter(
    private val dao: LibraryDao,
    private val storage: BibleStorage,
    private val sources: (Uri) -> PackSource,
    private val prober: DurationProber,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        versionId: String,
        verifyHashes: Boolean,
        isStopped: () -> Boolean = { false },
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportOutcome = withContext(Dispatchers.IO) {
        val version = dao.version(versionId) ?: return@withContext ImportOutcome.Failure("Unknown version $versionId")
        if (version.state == VersionState.READY) return@withContext ImportOutcome.Success
        val uri = version.sourceTreeUri ?: return@withContext fail(versionId, "The source folder is no longer known. Delete and import again.")

        val manifest = try {
            PackManifestCodec.decode(storage.manifest(versionId).readText())
        } catch (e: IOException) {
            return@withContext fail(versionId, "Saved manifest is missing: ${e.message}")
        } catch (e: ManifestFormatException) {
            return@withContext fail(versionId, e.message ?: "Saved manifest is invalid")
        }

        val files = dao.files(versionId)
        val pending = files.filter { !it.imported }
        val totalBytes = files.sumOf { it.sizeBytes }
        var doneBytes = files.filter { it.imported }.sumOf { it.sizeBytes }
        var doneFiles = files.size - pending.size
        onProgress(ImportProgress(doneFiles, files.size, doneBytes, totalBytes))

        if (pending.isNotEmpty()) {
            val source = try {
                sources(uri.toUri()).also { it.index() }
            } catch (e: SecurityException) {
                return@withContext fail(versionId, "Access to the source folder was lost. Pick it again to resume.")
            } catch (e: IOException) {
                return@withContext ImportOutcome.Retry("Couldn't list the source folder: ${e.message}")
            }
            val dir = storage.versionDir(versionId).canonicalFile

            for (f in pending) {
                if (isStopped()) return@withContext ImportOutcome.Cancelled
                val dest = File(dir, f.path).canonicalFile
                if (!dest.path.startsWith(dir.path + File.separator)) {
                    return@withContext fail(versionId, "Unsafe path in manifest: ${f.path}")
                }
                val outcome = try {
                    copy(source, f, dest, verifyHashes, isStopped)
                } catch (e: SecurityException) {
                    return@withContext fail(versionId, "Access to the source folder was lost. Pick it again to resume.")
                } catch (e: IOException) {
                    return@withContext ImportOutcome.Retry("Copying ${f.path} failed: ${e.message}")
                }
                if (outcome is ImportOutcome.Failure) return@withContext fail(versionId, outcome.reason)
                if (outcome != null) return@withContext outcome

                val duration = f.durationMs ?: prober.durationMs(dest)
                dao.markImported(f.id, duration)
                if (version.moveSource && !source.delete(f.path)) {
                    Log.w(TAG, "Couldn't delete source file ${f.path}; keeping the original")
                }
                doneFiles++
                doneBytes += f.sizeBytes
                onProgress(ImportProgress(doneFiles, files.size, doneBytes, totalBytes))
            }
            source.release()
        }

        val byPath = dao.files(versionId).associateBy { it.path }
        val chapters = manifest.chapters.map { c ->
            val file = byPath.getValue(c.file)
            val end = c.endMs ?: file.durationMs
            ChapterAudioEntity(versionId, c.book, c.chapter, file.id, c.startMs, c.endMs, end?.let { it - c.startMs })
        }
        dao.finishImport(versionId, chapters, clock())
        ImportOutcome.Success
    }

    /** Null on success; an outcome to return otherwise. */
    private fun copy(source: PackSource, f: AudioFileEntity, dest: File, verifyHashes: Boolean, isStopped: () -> Boolean): ImportOutcome? {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val digest = if (verifyHashes && f.sha256 != null) MessageDigest.getInstance("SHA-256") else null
        var count = 0L
        try {
            source.open(f.path).use { raw ->
                val input = if (digest != null) DigestInputStream(raw, digest) else raw
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (isStopped()) {
                            part.delete()
                            return ImportOutcome.Cancelled
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        count += n
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        if (count != f.sizeBytes) {
            part.delete()
            return ImportOutcome.Failure("${f.path} is $count bytes, the manifest says ${f.sizeBytes}")
        }
        if (digest != null && digest.digest().toHex() != f.sha256) {
            part.delete()
            return ImportOutcome.Failure("${f.path} doesn't match its SHA-256 in the manifest")
        }
        if (!part.renameTo(dest)) {
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Couldn't move ${part.name} into place")
        }
        return null
    }

    private suspend fun fail(versionId: String, reason: String): ImportOutcome {
        dao.setState(versionId, VersionState.FAILED, reason)
        return ImportOutcome.Failure(reason)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PackImporter"
        const val BUFFER_SIZE = 256 * 1024
    }
}
