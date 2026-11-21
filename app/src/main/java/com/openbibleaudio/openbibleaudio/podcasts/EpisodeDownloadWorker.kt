package com.openbibleaudio.openbibleaudio.podcasts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.openbibleaudio.openbibleaudio.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Downloads one feed's queued episodes, one at a time (spec 0003 §8.2). The queue is recomputed
 * after each episode, so a day completing or a mode change mid-run is picked up. With more than
 * [FOREGROUND_THRESHOLD] episodes to fetch it runs in the foreground with a progress
 * notification. A short top-up runs quietly.
 */
@HiltWorker
class EpisodeDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloads: EpisodeDownloads,
    private val downloader: EpisodeDownloader,
) : CoroutineWorker(context, params) {

    private val feedId get() = inputData.getString(KEY_FEED_ID).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val attempted = HashSet<String>()
        var total = 0
        var done = 0
        var foreground = false
        var lastProgressAt = 0L
        while (true) {
            val queue = downloads.queue(feedId).filterNot { it.guidHash in attempted }
            if (queue.isEmpty()) break
            if (total == 0) total = queue.size
            total = maxOf(total, done + queue.size)
            if (!foreground && queue.size > FOREGROUND_THRESHOLD) {
                foreground = runCatching { setForeground(foregroundInfo(done, total)) }.isSuccess
            }

            val episode = queue.first()
            attempted += episode.guidHash
            val outcome = downloader.download(episode) { bytes, expected ->
                val now = System.currentTimeMillis()
                if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                    lastProgressAt = now
                    setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_BYTES to bytes, KEY_EXPECTED to (expected ?: -1L)))
                }
            }
            when (outcome) {
                DownloadOutcome.Done -> {
                    done++
                    if (foreground) runCatching { setForeground(foregroundInfo(done, total)) }
                }
                // That episode is FAILED; carry on with the rest (spec 0003 §8.2).
                is DownloadOutcome.Gone -> Unit
                // Back off and resume from the .part later.
                is DownloadOutcome.Retry -> return Result.retry()
                // Stop: nothing more fits. A later trigger tries again.
                is DownloadOutcome.NoSpace -> return Result.failure(workDataOf(KEY_REASON to outcome.message))
            }
        }
        downloads.applyRetention()
        return Result.success()
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val context = applicationContext
        ensureChannel(context)
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.episodes_notification_title))
            .setContentText(if (total > 0) context.getString(R.string.episodes_notification_progress, done, total) else null)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_FEED_ID = "feedId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_BYTES = "bytes"
        const val KEY_EXPECTED = "expected"
        const val KEY_REASON = "reason"
        const val TAG = "podcast-download"
        const val FOREGROUND_THRESHOLD = 3
        private const val CHANNEL_ID = "podcast-downloads"
        private const val NOTIFICATION_ID = 1002
        private const val PROGRESS_INTERVAL_MS = 500L

        fun uniqueName(feedId: String) = "podcast-download/$feedId"

        /**
         * Queues a run for [feedId]. APPEND_OR_REPLACE: a running download finishes first, and the
         * appended run picks up whatever is still wanted then (spec 0003 §8.2). [replace] cancels
         * the running one instead (its `.part` is kept), e.g. when the network setting changed.
         */
        fun enqueue(workManager: WorkManager, feedId: String, unmeteredOnly: Boolean, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
                .setInputData(workDataOf(KEY_FEED_ID to feedId))
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(
                uniqueName(feedId),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.episodes_channel_name), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}
