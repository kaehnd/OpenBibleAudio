package com.openbibleaudio.openbibleaudio.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class SettingsRepositoryTest {
    private val dir: File = Files.createTempDirectory("settings").toFile()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun planAndPodcastDefaultsAndRoundTrip() = runBlocking {
        val d = repo.current()
        assertEquals(Settings(), d)
        assertEquals(1.0f, d.podcastSpeed)
        assertEquals(true, d.podcastUnmeteredOnly)
        assertEquals(7, d.podcastRetentionDays)
        assertEquals(false, d.continueToNextDay)

        repo.setPodcastSpeed(1.5f)
        repo.setBibleSpeed(0.9f)
        repo.setContinueToNextDay(true)
        repo.setAllowEpisodeStreaming(true)
        repo.setPodcastRetentionDays(-3)
        val s = repo.current()
        assertEquals(1.5f, s.podcastSpeed)
        assertEquals(0.9f, s.bibleSpeed) // separate speeds
        assertEquals(true, s.continueToNextDay)
        assertEquals(true, s.allowEpisodeStreaming)
        assertEquals(0, s.podcastRetentionDays)
    }
}
