package com.openbibleaudio.openbibleaudio.podcasts

import android.content.Context
import android.util.Xml
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

@Module
@InstallIn(SingletonComponent::class)
object PodcastModule {
    /** One client for feeds and (later) episode downloads, so they share a connection pool (AD-13). */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** `OpenBibleAudio/<versionName>`, with nothing about the user or device (spec 0003 §7.1). */
    @Provides
    @Singleton
    fun feedFetcher(@ApplicationContext context: Context, client: OkHttpClient): FeedFetcher {
        val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
        return FeedFetcher(client, "OpenBibleAudio/$versionName") { Xml.newPullParser() }
    }

    @Provides
    @Singleton
    fun podcasts(dao: PodcastDao, planDao: PlanDao, plans: PlanRepository, fetcher: FeedFetcher) =
        PodcastRepository(dao, planDao, plans, fetcher)
}
