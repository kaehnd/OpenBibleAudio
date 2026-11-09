package com.openbibleaudio.openbibleaudio

import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.library.ImportController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only access to app singletons for instrumented tests, which run against the real
 * Hilt graph (the same DB and WorkManager the playback service uses).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugEntryPoint {
    fun importController(): ImportController
    fun libraryRepository(): LibraryRepository
}
