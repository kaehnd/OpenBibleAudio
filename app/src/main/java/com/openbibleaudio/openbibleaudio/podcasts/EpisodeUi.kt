package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity

/** A day's episode as the enrollment screen lists it (spec 0003 §9.3). */
enum class DayEpisodeStatus { NONE, NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

data class DayEpisodeInfo(
    val status: DayEpisodeStatus,
    /** Null when the day has no episode. */
    val feedId: String?,
    val guidHash: String?,
    val title: String?,
    val pinned: Boolean,
    /** More than one upload matched this day; the latest is used (§7.3). */
    val duplicated: Boolean,
    /** Why the last download attempt failed, if it did. */
    val error: String?,
) {
    /** Unmatched or duplicated days are flagged so the listener can pin one (§7.4). */
    val needsAttention: Boolean get() = status == DayEpisodeStatus.NONE || (duplicated && !pinned)

    companion object {
        fun of(day: Int, view: EnrollmentEpisodeView): DayEpisodeInfo {
            val d = view.byDay[day] ?: return DayEpisodeInfo(DayEpisodeStatus.NONE, null, null, null, pinned = false, duplicated = false, error = null)
            val ep = d.episode
            return DayEpisodeInfo(statusOf(ep), ep.feedId, ep.guidHash, ep.title, d.pinned, day in view.duplicatedDays, ep.downloadError)
        }

        fun statusOf(ep: PodcastEpisodeEntity): DayEpisodeStatus = when (ep.downloadState) {
            DownloadState.NONE -> DayEpisodeStatus.NOT_DOWNLOADED
            DownloadState.QUEUED -> DayEpisodeStatus.QUEUED
            DownloadState.DOWNLOADING -> DayEpisodeStatus.DOWNLOADING
            DownloadState.DOWNLOADED -> DayEpisodeStatus.DOWNLOADED
            DownloadState.FAILED -> DayEpisodeStatus.FAILED
        }
    }
}

/** "312 / 365 downloaded" on an enrollment card, plus their size (spec 0003 §9.3, §8.3). */
data class EpisodeProgress(val downloaded: Int, val matched: Int, val dayCount: Int, val downloadedBytes: Long) {
    companion object {
        fun of(view: EnrollmentEpisodeView, dayCount: Int): EpisodeProgress {
            val episodes = view.byDay.values.map { it.episode }.distinctBy { it.guidHash }
            val done = episodes.filter { it.downloadState == DownloadState.DOWNLOADED }
            return EpisodeProgress(done.size, view.byDay.size, dayCount, done.sumOf { it.downloadedBytes ?: 0L })
        }
    }
}
