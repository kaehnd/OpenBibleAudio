package com.openbibleaudio.openbibleaudio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.openbibleaudio.openbibleaudio.data.db.CompletionDao
import com.openbibleaudio.openbibleaudio.data.db.LibraryDao
import com.openbibleaudio.openbibleaudio.data.db.OpenBibleAudioDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
private val Context.playbackStateStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): OpenBibleAudioDatabase =
        Room.databaseBuilder(context, OpenBibleAudioDatabase::class.java, OpenBibleAudioDatabase.NAME).build()

    @Provides
    fun libraryDao(db: OpenBibleAudioDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun completionDao(db: OpenBibleAudioDatabase): CompletionDao = db.completionDao()

    @Provides
    @Singleton
    fun settings(@ApplicationContext context: Context) = SettingsRepository(context.settingsStore)

    @Provides
    @Singleton
    fun resumption(@ApplicationContext context: Context) = ResumptionStore(context.playbackStateStore)

    @Provides
    @Singleton
    fun storage(@ApplicationContext context: Context) = BibleStorage(context.filesDir)

    @Provides
    @Singleton
    fun library(dao: LibraryDao, storage: BibleStorage, resumption: ResumptionStore) = LibraryRepository(dao, storage, resumption)
}
