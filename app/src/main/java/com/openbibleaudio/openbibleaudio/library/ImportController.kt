package com.openbibleaudio.openbibleaudio.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.storage.StorageManager
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.openbibleaudio.core.model.pack.PackManifest
import com.openbibleaudio.openbibleaudio.data.BibleStorage
import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.data.db.AudioFileEntity
import com.openbibleaudio.openbibleaudio.data.db.BibleVersionEntity
import com.openbibleaudio.openbibleaudio.data.db.LibraryDao
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What the import dialog shows (spec 0002 §6.1 step 5). */
sealed interface ImportPreview {
    data class Ready(
        val treeUri: Uri,
        val manifest: PackManifest,
        val manifestText: String,
        val inspection: PackInspection.Valid,
        val resuming: Boolean,
        val remainingBytes: Long,
        val largestRemainingBytes: Long,
        val freeBytes: Long,
        val canMove: Boolean,
    ) : ImportPreview {
        val chapterCount: Int get() = manifest.chapters.size
        fun requiredBytes(move: Boolean): Long =
            ImportSpace.requiredBytes(if (move) listOf(largestRemainingBytes) else listOf(remainingBytes), move)
        fun enoughSpace(move: Boolean): Boolean = freeBytes >= requiredBytes(move)
    }

    data class AlreadyImported(val abbreviation: String) : ImportPreview
    data class Invalid(val problems: List<String>) : ImportPreview
}

/** Status of an import job, for the Library card. */
data class ImportJob(val versionId: String, val running: Boolean, val done: Int, val total: Int)

/** Pack import entry points used by the UI (AD-6, spec 0002 §6). */
class ImportController(
    private val context: Context,
    private val dao: LibraryDao,
    private val library: LibraryRepository,
    private val storage: BibleStorage,
    private val sources: PackSourceFactory,
    private val workManager: WorkManager,
) {
    /** Running or queued imports, keyed by versionId. */
    val jobs: Flow<Map<String, ImportJob>> = workManager.getWorkInfosByTagFlow(ImportWorker.TAG).map { infos ->
        infos.filter { !it.state.isFinished }.mapNotNull { info ->
            val versionId = info.tags.firstOrNull { it.startsWith(VERSION_TAG) }?.removePrefix(VERSION_TAG) ?: return@mapNotNull null
            versionId to ImportJob(
                versionId,
                running = info.state == WorkInfo.State.RUNNING,
                done = info.progress.getInt(ImportWorker.KEY_DONE, 0),
                total = info.progress.getInt(ImportWorker.KEY_TOTAL, 0),
            )
        }.toMap()
    }

    /** Takes access to [treeUri], reads and checks its manifest. */
    suspend fun inspect(treeUri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val canWrite = try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            runCatching { resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            false
        }
        val source = sources.open(treeUri)
        when (val inspection = PackInspector.inspect(source)) {
            is PackInspection.Invalid -> {
                source.release()
                ImportPreview.Invalid(inspection.problems)
            }
            is PackInspection.Valid -> {
                val versionId = inspection.manifest.bible.versionId
                val existing = dao.version(versionId)
                if (existing?.state == VersionState.READY) {
                    source.release()
                    return@withContext ImportPreview.AlreadyImported(existing.abbreviation)
                }
                val verified = if (existing != null) dao.files(versionId).filter { it.imported }.mapTo(HashSet()) { it.path } else emptySet()
                val remaining = inspection.files.filter { it.path !in verified }.map { it.sizeBytes }
                ImportPreview.Ready(
                    treeUri = treeUri,
                    manifest = inspection.manifest,
                    manifestText = inspection.manifestText,
                    inspection = inspection,
                    resuming = existing != null,
                    remainingBytes = remaining.sum(),
                    largestRemainingBytes = remaining.maxOrNull() ?: 0,
                    freeBytes = freeBytes(),
                    canMove = canWrite && source.canDelete,
                )
            }
        }
    }

    /** Records the version and its files, saves the manifest and starts the worker. */
    suspend fun start(preview: ImportPreview.Ready, move: Boolean) = withContext(Dispatchers.IO) {
        val m = preview.manifest
        val versionId = m.bible.versionId
        storage.manifest(versionId).apply { parentFile?.mkdirs() }.writeText(preview.manifestText)
        dao.upsertVersion(
            BibleVersionEntity(
                versionId = versionId,
                abbreviation = m.bible.abbreviation,
                name = m.bible.name,
                language = m.bible.language,
                versification = m.bible.versification,
                narrator = m.bible.narrator,
                attribution = m.bible.attribution,
                state = VersionState.IMPORTING,
                totalBytes = preview.inspection.totalBytes,
                importedAt = null,
                sourceTreeUri = preview.treeUri.toString(),
                moveSource = move && preview.canMove,
            ),
        )
        dao.insertFiles(
            preview.inspection.files.map { AudioFileEntity(versionId = versionId, path = it.path, sizeBytes = it.sizeBytes, sha256 = it.sha256, durationMs = it.durationMs, imported = false) },
        )
        enqueue(versionId)
    }

    /** Resumes an IMPORTING or FAILED version with the folder it was started from. */
    suspend fun resume(versionId: String) {
        dao.setState(versionId, VersionState.IMPORTING, null)
        enqueue(versionId)
    }

    fun cancel(versionId: String) {
        workManager.cancelUniqueWork(ImportWorker.uniqueName(versionId))
    }

    suspend fun delete(versionId: String) {
        cancel(versionId)
        val uri = dao.version(versionId)?.sourceTreeUri
        library.delete(versionId)
        if (uri != null) runCatching { sources.open(uri.toUri()).release() }
    }

    private fun enqueue(versionId: String) {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf(ImportWorker.KEY_VERSION_ID to versionId))
            .addTag(ImportWorker.TAG)
            .addTag(VERSION_TAG + versionId)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(ImportWorker.uniqueName(versionId), ExistingWorkPolicy.KEEP, request)
    }

    private fun freeBytes(): Long {
        val sm = context.getSystemService(StorageManager::class.java)
        return runCatching { sm.getAllocatableBytes(sm.getUuidForPath(context.filesDir)) }.getOrElse { context.filesDir.usableSpace }
    }

    private companion object {
        const val VERSION_TAG = "version:"
    }
}
