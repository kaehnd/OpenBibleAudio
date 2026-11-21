package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.plans.EpisodeAvailability
import com.openbibleaudio.openbibleaudio.plans.EpisodeUnavailable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Which episode source a day queue uses, and where an episode starts (spec 0003 §6.2, §8.5, §8.6). */
class EpisodePlaybackTest {
    private fun ep(state: DownloadState = DownloadState.NONE, positionMs: Long = 0, completedAt: Long? = null) = PodcastEpisodeEntity(
        "feed00000000", "0123456789abcdef", "g", "Day 1 (Genesis 1-3) - Year 7", 0, "https://cdn.example.com/1.mp3",
        7_000_000, 468_000, "full", 7, 1, null, inFeed = true, downloadState = state, positionMs = positionMs, completedAt = completedAt,
    )

    private fun unavailable(a: EpisodeAvailability) = assertIs<EpisodeAvailability.Unavailable>(a).reason

    @Test
    fun aDownloadedFileWinsEvenWhenStreamingIsAllowed() {
        val a = EpisodePlayback.availability(ep(DownloadState.DOWNLOADED), "file:/data/x.mp3", allowStreaming = true, online = true)
        assertEquals("file:/data/x.mp3", assertIs<EpisodeAvailability.Local>(a).uri)
    }

    @Test
    fun aDownloadedRowWithoutItsFileIsNotPlayedFromDisk() {
        assertEquals(EpisodeUnavailable.NOT_DOWNLOADED, unavailable(EpisodePlayback.availability(ep(DownloadState.DOWNLOADED), null, false, true)))
    }

    @Test
    fun streamingIsOptInAndNeedsTheNetwork() {
        assertEquals(EpisodeUnavailable.NOT_DOWNLOADED, unavailable(EpisodePlayback.availability(ep(), null, allowStreaming = false, online = true)))
        assertEquals(EpisodeUnavailable.NOT_DOWNLOADED, unavailable(EpisodePlayback.availability(ep(), null, allowStreaming = true, online = false)))
        val s = assertIs<EpisodeAvailability.Stream>(EpisodePlayback.availability(ep(), null, allowStreaming = true, online = true))
        assertEquals("https://cdn.example.com/1.mp3", s.url)
    }

    @Test
    fun unmatchedAndGoneEpisodes() {
        assertEquals(EpisodeUnavailable.NOT_MATCHED, unavailable(EpisodePlayback.availability(null, null, true, true)))
        // A publisher 404 isn't streamed either: the URL is known to be dead.
        assertEquals(EpisodeUnavailable.NO_LONGER_AVAILABLE, unavailable(EpisodePlayback.availability(ep(DownloadState.FAILED), null, true, true)))
    }

    @Test
    fun resumesTwoSecondsBackUnlessComplete() {
        assertEquals(58_000, EpisodePlayback.startPositionMs(ep(positionMs = 60_000)))
        assertEquals(0, EpisodePlayback.startPositionMs(ep(positionMs = 1_500)))
        assertEquals(0, EpisodePlayback.startPositionMs(ep(positionMs = 60_000, completedAt = 1)))
    }

    @Test
    fun browseStates() {
        assertEquals(EpisodeBrowseState.DOWNLOADED, EpisodePlayback.browseState(EpisodeAvailability.Local("f", "g", "file:/x")))
        assertEquals(EpisodeBrowseState.STREAMING, EpisodePlayback.browseState(EpisodeAvailability.Stream("f", "g", "https://x")))
        assertEquals(EpisodeBrowseState.NONE, EpisodePlayback.browseState(EpisodeAvailability.Unavailable(EpisodeUnavailable.NOT_MATCHED)))
        assertEquals(EpisodeBrowseState.NOT_DOWNLOADED, EpisodePlayback.browseState(EpisodeAvailability.Unavailable(EpisodeUnavailable.NOT_DOWNLOADED)))
    }
}
