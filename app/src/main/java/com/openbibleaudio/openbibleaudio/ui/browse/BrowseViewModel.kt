package com.openbibleaudio.openbibleaudio.ui.browse

import com.openbibleaudio.openbibleaudio.podcasts.EpisodeDownloads
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.data.Settings
import com.openbibleaudio.openbibleaudio.data.SettingsRepository
import com.openbibleaudio.openbibleaudio.playback.ContinuousPlayScope
import com.openbibleaudio.openbibleaudio.playback.LibrarySnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Library snapshot for the phone's book and chapter pickers. */
@HiltViewModel
class BrowseViewModel @Inject constructor(library: LibraryRepository) : ViewModel() {
    val snapshot: StateFlow<LibrarySnapshot?> = library.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val downloads: EpisodeDownloads,
    library: LibraryRepository,
) : ViewModel() {
    val settings: StateFlow<Settings?> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val versions = library.versions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setScope(scope: ContinuousPlayScope) = viewModelScope.launch { repository.setContinuousPlayScope(scope) }
    fun setSeekBack(ms: Long) = viewModelScope.launch { repository.setSeekBackMs(ms) }
    fun setSeekForward(ms: Long) = viewModelScope.launch { repository.setSeekForwardMs(ms) }
    fun setVerifyHashes(verify: Boolean) = viewModelScope.launch { repository.setVerifyHashesOnImport(verify) }
    fun setContinueToNextDay(value: Boolean) = viewModelScope.launch { repository.setContinueToNextDay(value) }

    // Podcasts (spec 0003 §5, §9.3)
    fun setUnmeteredOnly(value: Boolean) = viewModelScope.launch {
        repository.setPodcastUnmeteredOnly(value)
        // Re-enqueue with the new network constraint (spec 0003 §8.2).
        downloads.startAll(replace = true)
    }
    fun setRetentionDays(days: Int) = viewModelScope.launch {
        repository.setPodcastRetentionDays(days)
        downloads.applyRetention()
    }
    fun setKeepPlayed(value: Boolean) = viewModelScope.launch {
        repository.setKeepPlayedEpisodes(value)
        if (!value) downloads.applyRetention()
    }
    fun setAllowStreaming(value: Boolean) = viewModelScope.launch { repository.setAllowEpisodeStreaming(value) }
    fun setPodcastSpeed(speed: Float) = viewModelScope.launch { repository.setPodcastSpeed(speed) }
}
