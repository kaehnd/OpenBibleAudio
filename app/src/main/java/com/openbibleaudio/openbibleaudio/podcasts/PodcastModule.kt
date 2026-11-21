package com.openbibleaudio.openbibleaudio.podcasts

import android.content.Context
import android.os.storage.StorageManager
import android.util.Xml
import androidx.work.WorkManager
import com.openbibleaudio.openbibleaudio.data.SettingsRepository
import com.openbibleaudio.openbibleaudio.library.DurationProber
import javax.inject.Qualifier
import com.openbibleaudio.openbibleaudio.data.db.PlanDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UserAgent

@Module
@InstallIn(SingletonComponent::class)
object PodcastModule {
    /** One client for feeds and episode downloads, so they share a connection pool (AD-13). */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** `OpenBibleAudio/<versionName>`, with nothing about the user or device (spec 0003 §7.1). */
    @Provides
    @Singleton
    @UserAgent
    fun userAgent(@ApplicationContext context: Context): String {
        val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
        return "OpenBibleAudio/$versionName"
    }

    @Provides
    @Singleton
    fun feedFetcher(client: OkHttpClient, @UserAgent userAgent: String) = FeedFetcher(client, userAgent) { Xml.newPullParser() }

    @Provides
    @Singleton
    fun storage(@ApplicationContext context: Context) = PodcastStorage(context.filesDir) {
        val sm = context.getSystemService(StorageManager::class.java)
        runCatching { sm.getAllocatableBytes(sm.getUuidForPath(context.filesDir)) }.getOrElse { context.filesDir.usableSpace }
    }

    @Provides
    @Singleton
    fun downloader(client: OkHttpClient, @UserAgent userAgent: String, dao: PodcastDao, storage: PodcastStorage) =
        EpisodeDownloader(client, userAgent, dao, storage, DurationProber.MEDIA_METADATA)

    @Provides
    @Singleton
    fun downloads(
        plans: PlanRepository,
        planDao: PlanDao,
        dao: PodcastDao,
        storage: PodcastStorage,
        settings: SettingsRepository,
        workManager: WorkManager,
    ) = EpisodeDownloads(plans, planDao, dao, storage, settings, { feedId, unmeteredOnly ->
        EpisodeDownloadWorker.enqueue(workManager, feedId, unmeteredOnly)
    })

    @Provides
    @Singleton
    fun podcasts(dao: PodcastDao, planDao: PlanDao, plans: PlanRepository, fetcher: FeedFetcher) =
        PodcastRepository(dao, planDao, plans, fetcher)
}
