package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.plans.EpisodeAvailability
import com.openbibleaudio.openbibleaudio.plans.EpisodeUnavailable

/** What Auto's Today subtitle says about the current day's episode (spec 0003 §9.1). */
enum class EpisodeBrowseState { DOWNLOADED, STREAMING, NOT_DOWNLOADED, NO_LONGER_AVAILABLE, NONE }

/** Episode playback rules (spec 0003 §6.2, §8.5, §8.6). Pure Kotlin. */
object EpisodePlayback {
    /** Back this far into a partly played episode, so the listener picks up the thread. */
    const val RESUME_BACK_MS = 2_000L

    /**
     * Whether [episode] (the day's episode, or null when none is matched) can be queued:
     * downloaded, else streamed when [allowStreaming] and [online], else why not.
     * [localUri] is the episode's file URI when that file exists.
     */
    fun availability(episode: PodcastEpisodeEntity?, localUri: String?, allowStreaming: Boolean, online: Boolean): EpisodeAvailability = when {
        episode == null -> EpisodeAvailability.Unavailable(EpisodeUnavailable.NOT_MATCHED)
        episode.downloadState == DownloadState.DOWNLOADED && localUri != null ->
            EpisodeAvailability.Local(episode.feedId, episode.guidHash, localUri)
        episode.downloadState == DownloadState.FAILED ->
            EpisodeAvailability.Unavailable(EpisodeUnavailable.NO_LONGER_AVAILABLE)
        allowStreaming && online -> EpisodeAvailability.Stream(episode.feedId, episode.guidHash, episode.enclosureUrl)
        else -> EpisodeAvailability.Unavailable(EpisodeUnavailable.NOT_DOWNLOADED)
    }

    /** Where an episode starts (spec 0003 §8.5): 2 s before where it stopped, or 0 once complete. */
    fun startPositionMs(episode: PodcastEpisodeEntity): Long =
        if (episode.completedAt != null) 0 else (episode.positionMs - RESUME_BACK_MS).coerceAtLeast(0)

    fun browseState(availability: EpisodeAvailability): EpisodeBrowseState = when (availability) {
        is EpisodeAvailability.Local -> EpisodeBrowseState.DOWNLOADED
        is EpisodeAvailability.Stream -> EpisodeBrowseState.STREAMING
        is EpisodeAvailability.Unavailable -> when (availability.reason) {
            EpisodeUnavailable.NO_PODCAST, EpisodeUnavailable.NOT_MATCHED -> EpisodeBrowseState.NONE
            EpisodeUnavailable.NOT_DOWNLOADED -> EpisodeBrowseState.NOT_DOWNLOADED
            EpisodeUnavailable.NO_LONGER_AVAILABLE -> EpisodeBrowseState.NO_LONGER_AVAILABLE
        }
    }
}
