package com.openbibleaudio.openbibleaudio.library

import android.content.Context
import androidx.work.WorkManager
import com.openbibleaudio.openbibleaudio.data.BibleStorage
import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.data.db.LibraryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    @Provides
    @Singleton
    fun packSources(@ApplicationContext context: Context) = PackSourceFactory(context)

    @Provides
    @Singleton
    fun importer(dao: LibraryDao, storage: BibleStorage, sources: PackSourceFactory) =
        PackImporter(dao, storage, sources::open, DurationProber.MEDIA_METADATA)

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun importController(
        @ApplicationContext context: Context,
        dao: LibraryDao,
        library: LibraryRepository,
        storage: BibleStorage,
        sources: PackSourceFactory,
        workManager: WorkManager,
    ) = ImportController(context, dao, library, storage, sources, workManager)

    @Provides
    @Singleton
    fun audioSource(library: LibraryRepository, storage: BibleStorage): BibleAudioSource = LocalBibleAudioSource(library, storage)
}
