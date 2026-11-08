package com.openbibleaudio.openbibleaudio.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.data.SettingsRepository
import com.openbibleaudio.openbibleaudio.data.db.LibraryDao
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Long-running, resumable import job (AD-6, spec 0002 §6.2). The work itself is in
 * [PackImporter]; this adds the foreground notification, progress and retry policy.
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val importer: PackImporter,
    private val settings: SettingsRepository,
    private val dao: LibraryDao,
) : CoroutineWorker(context, params) {

    private val versionId get() = inputData.getString(KEY_VERSION_ID).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    override suspend fun doWork(): Result = coroutineScope {
        val abbreviation = dao.version(versionId)?.abbreviation ?: versionId
        runCatching { setForeground(foregroundInfo(null, abbreviation)) }

        val latest = AtomicReference<ImportProgress?>(null)
        val reporter = launch {
            var shown: ImportProgress? = null
            while (true) {
                val p = latest.get()
                if (p != null && p != shown) {
                    shown = p
                    setProgress(workDataOf(KEY_DONE to p.doneFiles, KEY_TOTAL to p.totalFiles))
                    runCatching { setForeground(foregroundInfo(p, abbreviation)) }
                }
                delay(PROGRESS_INTERVAL_MS)
            }
        }

        val outcome = importer.run(
            versionId = versionId,
            verifyHashes = settings.current().verifyHashesOnImport,
            isStopped = { isStopped },
            onProgress = { latest.set(it) },
        )
        reporter.cancel()

        when (outcome) {
            ImportOutcome.Success -> Result.success()
            ImportOutcome.Cancelled -> Result.failure()
            is ImportOutcome.Failure -> Result.failure(workDataOf(KEY_REASON to outcome.reason))
            is ImportOutcome.Retry -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                dao.setState(versionId, VersionState.FAILED, outcome.reason)
                Result.failure(workDataOf(KEY_REASON to outcome.reason))
            } else {
                Result.retry()
            }
        }
    }

    private fun foregroundInfo(p: ImportProgress?, abbreviation: String = versionId): ForegroundInfo {
        ensureChannel(applicationContext)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val text = if (p == null) {
            applicationContext.getString(R.string.import_notification_starting, abbreviation)
        } else {
            applicationContext.getString(R.string.import_notification_progress, abbreviation, p.doneFiles, p.totalFiles)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.import_notification_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(p?.totalFiles ?: 0, p?.doneFiles ?: 0, p == null)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, applicationContext.getString(R.string.cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_VERSION_ID = "versionId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_REASON = "reason"
        const val TAG = "import"
        const val MAX_ATTEMPTS = 5
        private const val CHANNEL_ID = "import"
        private const val NOTIFICATION_ID = 1001
        private const val PROGRESS_INTERVAL_MS = 500L

        fun uniqueName(versionId: String) = "import/$versionId"

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.import_channel_name), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
