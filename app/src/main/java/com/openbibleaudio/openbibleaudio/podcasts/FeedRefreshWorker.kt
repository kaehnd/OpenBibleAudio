package com.openbibleaudio.openbibleaudio.podcasts

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Daily refresh of every active enrollment's podcast feed (spec 0003 §7.1). Needs a network
 * connection. A feed fetched within the last 12 h (e.g. when the Plans screen opened) is skipped.
 */
@HiltWorker
class FeedRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val podcasts: PodcastRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (podcasts.refreshActive()) Result.success() else Result.retry()

    companion object {
        const val UNIQUE_NAME = "podcast-feed-refresh"

        /** Idempotent: an existing schedule is kept. */
        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<FeedRefreshWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(15))
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
